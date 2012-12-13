package com.facebook.presto.sql.planner;

import com.facebook.presto.execution.ExchangePlanFragmentSource;
import com.facebook.presto.metadata.ColumnHandle;
import com.facebook.presto.metadata.FunctionHandle;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.TableHandle;
import com.facebook.presto.operator.AggregationOperator;
import com.facebook.presto.operator.FilterAndProjectOperator;
import com.facebook.presto.operator.FilterFunction;
import com.facebook.presto.operator.FilterFunctions;
import com.facebook.presto.operator.HashAggregationOperator;
import com.facebook.presto.operator.HashJoinOperator;
import com.facebook.presto.operator.LimitOperator;
import com.facebook.presto.operator.NewInMemoryOrderByOperator;
import com.facebook.presto.operator.Operator;
import com.facebook.presto.operator.OperatorStats;
import com.facebook.presto.operator.ProjectionFunction;
import com.facebook.presto.operator.ProjectionFunctions;
import com.facebook.presto.operator.SourceHashProvider;
import com.facebook.presto.operator.SourceHashProviderFactory;
import com.facebook.presto.operator.TopNOperator;
import com.facebook.presto.operator.aggregation.AggregationFunction;
import com.facebook.presto.operator.aggregation.AggregationFunctionStep;
import com.facebook.presto.operator.aggregation.AggregationFunctions;
import com.facebook.presto.operator.aggregation.Input;
import com.facebook.presto.sql.analyzer.Symbol;
import com.facebook.presto.sql.analyzer.Type;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.ExchangeNode;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.LimitNode;
import com.facebook.presto.sql.planner.plan.OutputNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.SortNode;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.planner.plan.TopNNode;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.QualifiedNameReference;
import com.facebook.presto.sql.tree.SortItem;
import com.facebook.presto.tuple.FieldOrderedTupleComparator;
import com.facebook.presto.tuple.TupleReadable;
import com.facebook.presto.util.IterableTransformer;
import com.facebook.presto.util.MoreFunctions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;

import javax.inject.Provider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Functions.forMap;
import static com.google.common.base.Preconditions.checkNotNull;

public class LocalExecutionPlanner
{
    private final Metadata metadata;
    private final PlanFragmentSourceProvider sourceProvider;
    private final Map<Symbol, Type> types;

    private final PlanFragmentSource split;
    private final Map<TableHandle, TableScanPlanFragmentSource> tableScans;
    private final OperatorStats operatorStats;

    private final Map<String, ExchangePlanFragmentSource> exchangeSources;

    private final SourceHashProviderFactory joinHashFactory;

    public LocalExecutionPlanner(Metadata metadata,
            PlanFragmentSourceProvider sourceProvider,
            Map<Symbol, Type> types,
            PlanFragmentSource split,
            Map<TableHandle, TableScanPlanFragmentSource> tableScans,
            Map<String, ExchangePlanFragmentSource> exchangeSources,
            OperatorStats operatorStats,
            SourceHashProviderFactory joinHashFactory)
    {
        this.tableScans = tableScans;
        this.operatorStats = Preconditions.checkNotNull(operatorStats, "operatorStats is null");
        this.metadata = checkNotNull(metadata, "metadata is null");
        this.sourceProvider = checkNotNull(sourceProvider, "sourceProvider is null");
        this.types = checkNotNull(types, "types is null");
        this.split = split;
        this.exchangeSources = ImmutableMap.copyOf(checkNotNull(exchangeSources, "exchangeSources is null"));
        this.joinHashFactory = checkNotNull(joinHashFactory, "joinHashFactory is null");
    }

    public Operator plan(PlanNode plan)
    {
        if (plan instanceof TableScanNode) {
            return createTableScan((TableScanNode) plan);
        }
        else if (plan instanceof ProjectNode) {
            return createProjectNode((ProjectNode) plan);
        }
        else if (plan instanceof FilterNode) {
            return createFilterNode((FilterNode) plan);
        }
        else if (plan instanceof OutputNode) {
            return createOutputPlan((OutputNode) plan);
        }
        else if (plan instanceof AggregationNode) {
            return createAggregationNode((AggregationNode) plan);
        }
        else if (plan instanceof LimitNode) {
            return createLimitNode((LimitNode) plan);
        }
        else if (plan instanceof TopNNode) {
            return createTopNNode((TopNNode) plan);
        }
        else if (plan instanceof SortNode) {
            return createSortPlan((SortNode) plan);
        }
        else if (plan instanceof ExchangeNode) {
            return createExchange((ExchangeNode) plan);
        }
        else if (plan instanceof JoinNode) {
            return createJoinNode((JoinNode) plan);
        }

        throw new UnsupportedOperationException("not yet implemented: " + plan.getClass().getName());
    }

    private Operator createExchange(ExchangeNode node)
    {
        int sourceFragmentId = node.getSourceFragmentId();
        ExchangePlanFragmentSource source = exchangeSources.get(String.valueOf(sourceFragmentId));
        Preconditions.checkState(source != null, "Exchange source for fragment %s was not found: available sources %s", sourceFragmentId, exchangeSources.keySet());
        return sourceProvider.createDataStream(source, ImmutableList.<ColumnHandle>of());
    }

    private Operator createOutputPlan(OutputNode node)
    {
        PlanNode source = node.getSource();
        Operator sourceOperator = plan(node.getSource());

        Map<Symbol, Integer> symbolToChannelMappings = mapSymbolsToChannels(source.getOutputSymbols());

        List<Symbol> resultSymbols = Lists.transform(node.getColumnNames(), forMap(node.getAssignments()));
        if (resultSymbols.equals(source.getOutputSymbols())) {
            // no need for a projection -- the output matches the result of the underlying operator
            return sourceOperator;
        }

        List<ProjectionFunction> projections = new ArrayList<>();
        for (Symbol symbol : resultSymbols) {
            ProjectionFunction function = new InterpretedProjectionFunction(types.get(symbol),
                    new QualifiedNameReference(symbol.toQualifiedName()),
                    symbolToChannelMappings);

            projections.add(function);
        }

        return new FilterAndProjectOperator(sourceOperator, FilterFunctions.TRUE_FUNCTION, projections);
    }

    private Operator createTopNNode(TopNNode node)
    {
        Preconditions.checkArgument(node.getOrderBy().size() == 1, "Order by multiple fields not yet supported");
        Symbol orderBySymbol = Iterables.getOnlyElement(node.getOrderBy());

        PlanNode source = node.getSource();

        Map<Symbol, Integer> symbolToChannelMappings = mapSymbolsToChannels(source.getOutputSymbols());

        List<ProjectionFunction> projections = new ArrayList<>();
        for (int i = 0; i < node.getOutputSymbols().size(); i++) {
            Symbol symbol = node.getOutputSymbols().get(i);
            ProjectionFunction function = ProjectionFunctions.singleColumn(types.get(symbol).getRawType(), symbolToChannelMappings.get(symbol), 0);
            projections.add(function);
        }

        Ordering<TupleReadable> ordering = Ordering.from(FieldOrderedTupleComparator.INSTANCE);
        if (node.getOrderings().get(orderBySymbol) == SortItem.Ordering.ASCENDING) {
            ordering = ordering.reverse();
        }

        return new TopNOperator(plan(source), (int) node.getCount(), symbolToChannelMappings.get(orderBySymbol), projections, ordering);
    }

    private Operator createSortPlan(SortNode node)
    {
        PlanNode source = node.getSource();

        Map<Symbol, Integer> symbolToChannelMappings = mapSymbolsToChannels(source.getOutputSymbols());

        int[] outputChannels = new int[node.getOutputSymbols().size()];
        for (int i = 0; i < node.getOutputSymbols().size(); i++) {
            Symbol symbol = node.getOutputSymbols().get(i);
            outputChannels[i] = symbolToChannelMappings.get(symbol);
        }

        Preconditions.checkArgument(node.getOrderBy().size() == 1, "Order by multiple fields not yet supported");

        Symbol orderBySymbol = Iterables.getOnlyElement(node.getOrderBy());
        int[] sortFields = new int[] { 0 };
        boolean[] sortOrder = new boolean[] { node.getOrderings().get(orderBySymbol) == SortItem.Ordering.ASCENDING};

        return new NewInMemoryOrderByOperator(plan(source), symbolToChannelMappings.get(orderBySymbol), outputChannels, 1_000_000, sortFields, sortOrder);
    }

    private Operator createLimitNode(LimitNode node)
    {
        PlanNode source = node.getSource();
        return new LimitOperator(plan(source), node.getCount());
    }

    private Operator createAggregationNode(AggregationNode node)
    {
        PlanNode source = node.getSource();
        Operator sourceOperator = plan(source);

        Map<Symbol, Integer> symbolToChannelMappings = mapSymbolsToChannels(source.getOutputSymbols());

        List<Provider<AggregationFunctionStep>> aggregationFunctions = new ArrayList<>();
        for (Map.Entry<Symbol, FunctionCall> entry : node.getAggregations().entrySet()) {
            List<Input> arguments = new ArrayList<>();
            for (Expression argument : entry.getValue().getArguments()) {
                int channel = symbolToChannelMappings.get(Symbol.fromQualifiedName(((QualifiedNameReference) argument).getName()));
                int field = 0; // TODO: support composite channels

                arguments.add(new Input(channel, field));
            }

            FunctionHandle functionHandle = node.getFunctions().get(entry.getKey());
            Provider<AggregationFunction> boundFunction = metadata.getFunction(functionHandle).bind(arguments);

            Provider<AggregationFunctionStep> aggregation;
            switch (node.getStep()) {
                case PARTIAL:
                    aggregation = AggregationFunctions.partialAggregation(boundFunction);
                    break;
                case FINAL:
                    aggregation = AggregationFunctions.finalAggregation(boundFunction);
                    break;
                case SINGLE:
                    aggregation = AggregationFunctions.singleNodeAggregation(boundFunction);
                    break;
                default:
                    throw new UnsupportedOperationException("not yet implemented: " + node.getStep());
            }

            aggregationFunctions.add(aggregation);
        }

        List<ProjectionFunction> projections = new ArrayList<>();
        for (int i = 0; i < node.getOutputSymbols().size(); ++i) {
            Symbol symbol = node.getOutputSymbols().get(i);
            projections.add(ProjectionFunctions.singleColumn(types.get(symbol).getRawType(), i, 0));
        }

        if (node.getGroupBy().isEmpty()) {
            return new AggregationOperator(sourceOperator, aggregationFunctions, projections);
        }

        Preconditions.checkArgument(node.getGroupBy().size() <= 1, "Only single GROUP BY key supported at this time");
        Symbol groupBySymbol = Iterables.getOnlyElement(node.getGroupBy());
        return new HashAggregationOperator(sourceOperator, symbolToChannelMappings.get(groupBySymbol), aggregationFunctions, projections);
    }

    private Operator createFilterNode(FilterNode node)
    {
        PlanNode source = node.getSource();
        Operator sourceOperator = plan(source);

        Map<Symbol, Integer> symbolToChannelMappings = mapSymbolsToChannels(source.getOutputSymbols());

        FilterFunction filter = new InterpretedFilterFunction(node.getPredicate(), symbolToChannelMappings);

        List<ProjectionFunction> projections = new ArrayList<>();
        for (int i = 0; i < node.getOutputSymbols().size(); i++) {
            Symbol symbol = node.getOutputSymbols().get(i);
            ProjectionFunction function = ProjectionFunctions.singleColumn(types.get(symbol).getRawType(), symbolToChannelMappings.get(symbol), 0);
            projections.add(function);
        }

        return new FilterAndProjectOperator(sourceOperator, filter, projections);
    }

    private Operator createProjectNode(final ProjectNode node)
    {
        PlanNode source = node.getSource();
        Operator sourceOperator = plan(node.getSource());

        Map<Symbol, Integer> symbolToChannelMappings = mapSymbolsToChannels(source.getOutputSymbols());

        List<ProjectionFunction> projections = new ArrayList<>();
        for (int i = 0; i < node.getExpressions().size(); i++) {
            Symbol symbol = node.getOutputSymbols().get(i);
            Expression expression = node.getExpressions().get(i);
            ProjectionFunction function = new InterpretedProjectionFunction(types.get(symbol), expression, symbolToChannelMappings);
            projections.add(function);
        }

        return new FilterAndProjectOperator(sourceOperator, FilterFunctions.TRUE_FUNCTION, projections);
    }

    private Operator createTableScan(TableScanNode node)
    {
        List<ColumnHandle> columns = IterableTransformer.on(node.getAssignments().entrySet())
                .orderBy(Ordering.explicit(node.getOutputSymbols()).onResultOf(MoreFunctions.<Symbol, ColumnHandle>keyGetter()))
                .transform(MoreFunctions.<Symbol, ColumnHandle>valueGetter())
                .list();

        // table scan only works with a split
        Preconditions.checkState(split != null || tableScans != null, "This fragment does not have a split");

        PlanFragmentSource tableSplit = split;
        if (tableSplit == null) {
            tableSplit = tableScans.get(node.getTable());
        }

        Operator operator = sourceProvider.createDataStream(tableSplit, columns);
        return operator;
    }

    private Operator createJoinNode(JoinNode node)
    {
        ComparisonExpression comparison = (ComparisonExpression) node.getCriteria();
        Symbol first = Symbol.fromQualifiedName(((QualifiedNameReference) comparison.getLeft()).getName());
        Symbol second = Symbol.fromQualifiedName(((QualifiedNameReference) comparison.getRight()).getName());

        Map<Symbol, Integer> probeMappings = mapSymbolsToChannels(node.getLeft().getOutputSymbols());
        Map<Symbol, Integer> buildMappings = mapSymbolsToChannels(node.getRight().getOutputSymbols());

        int probeChannel;
        int buildChannel;
        if (node.getLeft().getOutputSymbols().contains(first)) {
            probeChannel = probeMappings.get(first);
            buildChannel = buildMappings.get(second);
        }
        else {
            probeChannel = probeMappings.get(second);
            buildChannel = buildMappings.get(first);
        }

        SourceHashProvider hashProvider = joinHashFactory.getSourceHashProvider(node, this, buildChannel, operatorStats);
        Operator leftOperator = plan(node.getLeft());
        HashJoinOperator operator = new HashJoinOperator(hashProvider, leftOperator, probeChannel);
        return operator;
    }

    private Map<Symbol, Integer> mapSymbolsToChannels(List<Symbol> outputs)
    {
        Map<Symbol, Integer> symbolToChannelMappings = new HashMap<>();
        for (int i = 0; i < outputs.size(); i++) {
            symbolToChannelMappings.put(outputs.get(i), i);
        }
        return symbolToChannelMappings;
    }
}