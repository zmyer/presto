/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner;

import com.facebook.presto.Session;
import com.facebook.presto.execution.StageInfo;
import com.facebook.presto.execution.StageStats;
import com.facebook.presto.execution.TaskInfo;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.OperatorNotFoundException;
import com.facebook.presto.metadata.Signature;
import com.facebook.presto.metadata.TableHandle;
import com.facebook.presto.metadata.TableLayout;
import com.facebook.presto.operator.OperatorStats;
import com.facebook.presto.operator.PipelineStats;
import com.facebook.presto.operator.TaskStats;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.predicate.Domain;
import com.facebook.presto.spi.predicate.Marker;
import com.facebook.presto.spi.predicate.NullableValue;
import com.facebook.presto.spi.predicate.Range;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.FunctionInvoker;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.ApplyNode;
import com.facebook.presto.sql.planner.plan.AssignUniqueId;
import com.facebook.presto.sql.planner.plan.Assignments;
import com.facebook.presto.sql.planner.plan.DeleteNode;
import com.facebook.presto.sql.planner.plan.DistinctLimitNode;
import com.facebook.presto.sql.planner.plan.EnforceSingleRowNode;
import com.facebook.presto.sql.planner.plan.ExceptNode;
import com.facebook.presto.sql.planner.plan.ExchangeNode;
import com.facebook.presto.sql.planner.plan.ExchangeNode.Scope;
import com.facebook.presto.sql.planner.plan.ExplainAnalyzeNode;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.GroupIdNode;
import com.facebook.presto.sql.planner.plan.IndexJoinNode;
import com.facebook.presto.sql.planner.plan.IndexSourceNode;
import com.facebook.presto.sql.planner.plan.IntersectNode;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.LimitNode;
import com.facebook.presto.sql.planner.plan.MarkDistinctNode;
import com.facebook.presto.sql.planner.plan.MetadataDeleteNode;
import com.facebook.presto.sql.planner.plan.OutputNode;
import com.facebook.presto.sql.planner.plan.PlanFragmentId;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.facebook.presto.sql.planner.plan.PlanVisitor;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.RemoteSourceNode;
import com.facebook.presto.sql.planner.plan.RowNumberNode;
import com.facebook.presto.sql.planner.plan.SampleNode;
import com.facebook.presto.sql.planner.plan.SemiJoinNode;
import com.facebook.presto.sql.planner.plan.SortNode;
import com.facebook.presto.sql.planner.plan.TableFinishNode;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.planner.plan.TableWriterNode;
import com.facebook.presto.sql.planner.plan.TopNNode;
import com.facebook.presto.sql.planner.plan.TopNRowNumberNode;
import com.facebook.presto.sql.planner.plan.UnionNode;
import com.facebook.presto.sql.planner.plan.UnnestNode;
import com.facebook.presto.sql.planner.plan.ValuesNode;
import com.facebook.presto.sql.planner.plan.WindowNode;
import com.facebook.presto.sql.tree.ComparisonExpression;
import com.facebook.presto.sql.tree.ComparisonExpressionType;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.FrameBound;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.SymbolReference;
import com.facebook.presto.sql.tree.Window;
import com.facebook.presto.sql.tree.WindowFrame;
import com.facebook.presto.util.GraphvizPrinter;
import com.google.common.base.CaseFormat;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import io.airlift.slice.Slice;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.facebook.presto.execution.StageInfo.getAllStages;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static com.facebook.presto.sql.planner.DomainUtils.simplifyDomain;
import static com.facebook.presto.sql.planner.SystemPartitioningHandle.SINGLE_DISTRIBUTION;
import static com.facebook.presto.util.ImmutableCollectors.toImmutableList;
import static com.google.common.base.CaseFormat.UPPER_UNDERSCORE;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.collect.Lists.reverse;
import static io.airlift.units.DataSize.Unit.BYTE;
import static io.airlift.units.DataSize.succinctBytes;
import static io.airlift.units.DataSize.succinctDataSize;
import static java.lang.Double.isFinite;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;

public class PlanPrinter
{
    private final StringBuilder output = new StringBuilder();
    private final Metadata metadata;
    private final Optional<Map<PlanNodeId, PlanNodeStats>> stats;

    private PlanPrinter(PlanNode plan, Map<Symbol, Type> types, Metadata metadata, Session sesion)
    {
        this(plan, types, metadata, sesion, 0);
    }

    private PlanPrinter(PlanNode plan, Map<Symbol, Type> types, Metadata metadata, Session session, int indent)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(types, "types is null");
        requireNonNull(metadata, "metadata is null");

        this.metadata = metadata;
        this.stats = Optional.empty();

        Visitor visitor = new Visitor(types, session);
        plan.accept(visitor, indent);
    }

    private PlanPrinter(PlanNode plan, Map<Symbol, Type> types, Metadata metadata, Session session, Map<PlanNodeId, PlanNodeStats> stats, int indent)
    {
        requireNonNull(plan, "plan is null");
        requireNonNull(types, "types is null");
        requireNonNull(metadata, "metadata is null");

        this.metadata = metadata;
        this.stats = Optional.of(stats);

        Visitor visitor = new Visitor(types, session);
        plan.accept(visitor, indent);
    }

    @Override
    public String toString()
    {
        return output.toString();
    }

    public static String textLogicalPlan(PlanNode plan, Map<Symbol, Type> types, Metadata metadata, Session session)
    {
        return new PlanPrinter(plan, types, metadata, session).toString();
    }

    public static String textLogicalPlan(PlanNode plan, Map<Symbol, Type> types, Metadata metadata, Session session, int indent)
    {
        return new PlanPrinter(plan, types, metadata, session, indent).toString();
    }

    public static String textLogicalPlan(PlanNode plan, Map<Symbol, Type> types, Metadata metadata, Session session, Map<PlanNodeId, PlanNodeStats> stats, int indent)
    {
        return new PlanPrinter(plan, types, metadata, session, stats, indent).toString();
    }

    public static String textDistributedPlan(List<StageInfo> stages, Metadata metadata, Session session)
    {
        StringBuilder builder = new StringBuilder();
        List<StageInfo> allStages = stages.stream()
                .flatMap(stage -> getAllStages(Optional.of(stage)).stream())
                .collect(toImmutableList());
        for (StageInfo stageInfo : allStages) {
            Map<PlanNodeId, PlanNodeStats> aggregatedStats = new HashMap<>();
            List<PlanNodeStats> planNodeStats = stageInfo.getTasks().stream()
                    .map(TaskInfo::getStats)
                    .flatMap(taskStats -> getPlanNodeStats(taskStats).stream())
                    .collect(toList());
            for (PlanNodeStats stats : planNodeStats) {
                aggregatedStats.merge(stats.getPlanNodeId(), stats, PlanNodeStats::merge);
            }

            builder.append(formatFragment(metadata, session, stageInfo.getPlan(), Optional.of(stageInfo.getStageStats()), Optional.of(aggregatedStats)));
        }

        return builder.toString();
    }

    private static List<PlanNodeStats> getPlanNodeStats(TaskStats taskStats)
    {
        // Best effort to reconstruct the plan nodes from operators.
        // Because stats are collected separately from query execution,
        // it's possible that some or all of them are missing or out of date.
        // For example, a LIMIT clause can cause a query to finish before stats
        // are collected from the leaf stages.
        Map<PlanNodeId, Long> inputPositions = new HashMap<>();
        Map<PlanNodeId, Long> inputBytes = new HashMap<>();
        Map<PlanNodeId, Long> outputPositions = new HashMap<>();
        Map<PlanNodeId, Long> outputBytes = new HashMap<>();
        Map<PlanNodeId, Long> wallMillis = new HashMap<>();

        for (PipelineStats pipelineStats : taskStats.getPipelines()) {
            // Due to eventual consistently collected stats, these could be empty
            if (pipelineStats.getOperatorSummaries().isEmpty()) {
                continue;
            }

            Set<PlanNodeId> processedNodes = new HashSet<>();
            PlanNodeId inputPlanNode = pipelineStats.getOperatorSummaries().iterator().next().getPlanNodeId();
            PlanNodeId outputPlanNode = getLast(pipelineStats.getOperatorSummaries()).getPlanNodeId();

            // Gather input statistics
            for (OperatorStats operatorStats : pipelineStats.getOperatorSummaries()) {
                PlanNodeId planNodeId = operatorStats.getPlanNodeId();

                long wall = operatorStats.getAddInputWall().toMillis() + operatorStats.getGetOutputWall().toMillis() + operatorStats.getFinishWall().toMillis();
                wallMillis.merge(planNodeId, wall, Long::sum);

                // A pipeline like hash build before join might link to another "internal" pipelines which provide actual input for this plan node
                if (operatorStats.getPlanNodeId().equals(inputPlanNode) && !pipelineStats.isInputPipeline()) {
                    continue;
                }
                if (processedNodes.contains(planNodeId)) {
                    continue;
                }

                inputPositions.merge(planNodeId, operatorStats.getInputPositions(), Long::sum);
                inputBytes.merge(planNodeId, operatorStats.getInputDataSize().toBytes(), Long::sum);
                processedNodes.add(planNodeId);
            }

            // Gather output statistics
            processedNodes.clear();
            for (OperatorStats operatorStats : reverse(pipelineStats.getOperatorSummaries())) {
                PlanNodeId planNodeId = operatorStats.getPlanNodeId();

                // An "internal" pipeline like a hash build, links to another pipeline which is the actual output for this plan node
                if (operatorStats.getPlanNodeId().equals(outputPlanNode) && !pipelineStats.isOutputPipeline()) {
                    continue;
                }
                if (processedNodes.contains(planNodeId)) {
                    continue;
                }

                outputPositions.merge(planNodeId, operatorStats.getOutputPositions(), Long::sum);
                outputBytes.merge(planNodeId, operatorStats.getOutputDataSize().toBytes(), Long::sum);
                processedNodes.add(planNodeId);
            }
        }

        List<PlanNodeStats> stats = new ArrayList<>();
        for (Map.Entry<PlanNodeId, Long> entry : wallMillis.entrySet()) {
            PlanNodeId planNodeId = entry.getKey();
            stats.add(new PlanNodeStats(
                    entry.getKey(),
                    new Duration(entry.getValue(), MILLISECONDS),
                    inputPositions.get(planNodeId),
                    succinctDataSize(inputBytes.get(planNodeId), BYTE),
                    // It's possible there will be no output stats because all the pipelines that we observed were non-output.
                    // For example in a query like SELECT * FROM a JOIN b ON c = d LIMIT 1
                    // It's possible to observe stats after the build starts, but before the probe does
                    // and therefore only have wall time, but no output stats
                    outputPositions.getOrDefault(planNodeId, 0L),
                    succinctDataSize(outputBytes.getOrDefault(planNodeId, 0L), BYTE)));
        }
        return stats;
    }

    public static String textDistributedPlan(SubPlan plan, Metadata metadata, Session session)
    {
        StringBuilder builder = new StringBuilder();
        for (PlanFragment fragment : plan.getAllFragments()) {
            builder.append(formatFragment(metadata, session, fragment, Optional.empty(), Optional.empty()));
        }

        return builder.toString();
    }

    private static String formatFragment(Metadata metadata, Session session, PlanFragment fragment, Optional<StageStats> stageStats, Optional<Map<PlanNodeId, PlanNodeStats>> planNodeStats)
    {
        StringBuilder builder = new StringBuilder();
        builder.append(format("Fragment %s [%s]\n",
                fragment.getId(),
                fragment.getPartitioning()));

        if (stageStats.isPresent()) {
            builder.append(indentString(1))
                    .append(format("Cost: CPU %s, Input: %s (%s), Output: %s (%s)\n",
                            stageStats.get().getTotalCpuTime(),
                            formatPositions(stageStats.get().getProcessedInputPositions()),
                            stageStats.get().getProcessedInputDataSize(),
                            formatPositions(stageStats.get().getOutputPositions()),
                            stageStats.get().getOutputDataSize()));
        }

        PartitioningScheme partitioningScheme = fragment.getPartitioningScheme();
        builder.append(indentString(1))
                .append(format("Output layout: [%s]\n",
                        Joiner.on(", ").join(partitioningScheme.getOutputLayout())));

        boolean replicateNulls = partitioningScheme.isReplicateNulls();
        List<String> arguments = partitioningScheme.getPartitioning().getArguments().stream()
                .map(argument -> {
                    if (argument.isConstant()) {
                        NullableValue constant = argument.getConstant();
                        String printableValue = castToVarchar(constant.getType(), constant.getValue(), metadata, session);
                        return constant.getType().getDisplayName() + "(" + printableValue + ")";
                    }
                    return argument.getColumn().toString();
                })
                .collect(toImmutableList());
        builder.append(indentString(1));
        if (replicateNulls) {
            builder.append(format("Output partitioning: %s (replicate nulls) [%s]%s\n",
                    partitioningScheme.getPartitioning().getHandle(),
                    Joiner.on(", ").join(arguments),
                    formatHash(partitioningScheme.getHashColumn())));
        }
        else {
            builder.append(format("Output partitioning: %s [%s]%s\n",
                    partitioningScheme.getPartitioning().getHandle(),
                    Joiner.on(", ").join(arguments),
                    formatHash(partitioningScheme.getHashColumn())));
        }

        if (stageStats.isPresent()) {
            builder.append(textLogicalPlan(fragment.getRoot(), fragment.getSymbols(), metadata, session, planNodeStats.get(), 1))
                    .append("\n");
        }
        else {
            builder.append(textLogicalPlan(fragment.getRoot(), fragment.getSymbols(), metadata, session, 1))
                    .append("\n");
        }

        return builder.toString();
    }

    public static String graphvizLogicalPlan(PlanNode plan, Map<Symbol, Type> types)
    {
        PlanFragment fragment = new PlanFragment(
                new PlanFragmentId("graphviz_plan"),
                plan,
                types,
                SINGLE_DISTRIBUTION,
                ImmutableList.of(plan.getId()),
                new PartitioningScheme(Partitioning.create(SINGLE_DISTRIBUTION, ImmutableList.of()), plan.getOutputSymbols()));
        return GraphvizPrinter.printLogical(ImmutableList.of(fragment));
    }

    public static String graphvizDistributedPlan(SubPlan plan)
    {
        return GraphvizPrinter.printDistributed(plan);
    }

    private void print(int indent, String format, Object... args)
    {
        String value;

        if (args.length == 0) {
            value = format;
        }
        else {
            value = format(format, args);
        }
        output.append(indentString(indent)).append(value).append('\n');
    }

    private void print(int indent, String format, List<Object> args)
    {
        print(indent, format, args.toArray(new Object[args.size()]));
    }

    private void printStats(int intent, PlanNodeId planNodeId)
    {
        printStats(intent, planNodeId, false, false);
    }

    private void printStats(int indent, PlanNodeId planNodeId, boolean printInput, boolean printFiltered)
    {
        if (!stats.isPresent()) {
            return;
        }

        long totalMillis = stats.get().values().stream()
                .mapToLong(node -> node.getWallTime().toMillis())
                .sum();

        PlanNodeStats nodeStats = stats.get().get(planNodeId);
        if (nodeStats == null) {
            output.append(indentString(indent));
            output.append("Cost: ?");
            if (printInput) {
                output.append(", Input: ? lines (?B)");
            }
            output.append(", Output: ? lines (?B)");
            if (printFiltered) {
                output.append(", Filtered: ?%");
            }
            output.append('\n');
            return;
        }

        double fraction = (nodeStats.getWallTime().toMillis()) / (double) totalMillis;
        String fractionString;
        if (isFinite(fraction)) {
            fractionString = format(Locale.US, "%.2f%%", 100.0 * fraction);
        }
        else {
            fractionString = "?";
        }

        output.append(indentString(indent));
        output.append("Cost: " + fractionString);
        if (printInput) {
            output.append(format(", Input: %s (%s)",
                    formatPositions(nodeStats.getInputPositions()),
                    nodeStats.getInputDataSize().toString()));
        }
        output.append(format(", Output: %s (%s)",
                formatPositions(nodeStats.getOutputPositions()),
                nodeStats.getOutputDataSize().toString()));
        if (printFiltered) {
            double filtered = 100.0 * (nodeStats.getInputPositions() - nodeStats.getOutputPositions()) / nodeStats.getInputPositions();
            String filteredString;
            if (isFinite(filtered)) {
                filteredString = format(Locale.US, "%.2f%%", filtered);
            }
            else {
                filteredString = "?";
            }

            output.append(", Filtered: " + filteredString);
        }
        output.append('\n');
    }

    private static String formatPositions(long positions)
    {
        if (positions == 1) {
            return "1 row";
        }

        return positions + " rows";
    }

    private static String indentString(int indent)
    {
        return Strings.repeat("    ", indent);
    }

    private class Visitor
            extends PlanVisitor<Integer, Void>
    {
        private final Map<Symbol, Type> types;
        private final Session session;

        @SuppressWarnings("AssignmentToCollectionOrArrayFieldFromParameter")
        public Visitor(Map<Symbol, Type> types, Session session)
        {
            this.types = types;
            this.session = session;
        }

        @Override
        public Void visitExplainAnalyze(ExplainAnalyzeNode node, Integer indent)
        {
            print(indent, "- ExplainAnalyze => [%s]", formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());
            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitJoin(JoinNode node, Integer indent)
        {
            List<Expression> joinExpressions = new ArrayList<>();
            for (JoinNode.EquiJoinClause clause : node.getCriteria()) {
                joinExpressions.add(new ComparisonExpression(ComparisonExpressionType.EQUAL,
                        clause.getLeft().toSymbolReference(),
                        clause.getRight().toSymbolReference()));
            }
            node.getFilter().ifPresent(expression -> joinExpressions.add(expression));

            // Check if the node is actually a cross join node
            if (node.getType() == JoinNode.Type.INNER && joinExpressions.isEmpty()) {
                print(indent, "- CrossJoin => [%s]", formatOutputs(node.getOutputSymbols()));
            }
            else {
                print(indent, "- %s[%s]%s => [%s]",
                        node.getType().getJoinLabel(),
                        Joiner.on(" AND ").join(joinExpressions),
                        formatHash(node.getLeftHashSymbol(), node.getRightHashSymbol()),
                        formatOutputs(node.getOutputSymbols()));
            }

            printStats(indent + 2, node.getId());
            node.getLeft().accept(this, indent + 1);
            node.getRight().accept(this, indent + 1);

            return null;
        }

        @Override
        public Void visitSemiJoin(SemiJoinNode node, Integer indent)
        {
            print(indent, "- SemiJoin[%s = %s]%s => [%s]",
                    node.getSourceJoinSymbol(),
                    node.getFilteringSourceJoinSymbol(),
                    formatHash(node.getSourceHashSymbol(), node.getFilteringSourceHashSymbol()),
                    formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());
            node.getSource().accept(this, indent + 1);
            node.getFilteringSource().accept(this, indent + 1);

            return null;
        }

        @Override
        public Void visitIndexSource(IndexSourceNode node, Integer indent)
        {
            print(indent, "- IndexSource[%s, lookup = %s] => [%s]", node.getIndexHandle(), node.getLookupSymbols(), formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());
            for (Map.Entry<Symbol, ColumnHandle> entry : node.getAssignments().entrySet()) {
                if (node.getOutputSymbols().contains(entry.getKey())) {
                    print(indent + 2, "%s := %s", entry.getKey(), entry.getValue());
                }
            }
            return null;
        }

        @Override
        public Void visitIndexJoin(IndexJoinNode node, Integer indent)
        {
            List<Expression> joinExpressions = new ArrayList<>();
            for (IndexJoinNode.EquiJoinClause clause : node.getCriteria()) {
                joinExpressions.add(new ComparisonExpression(ComparisonExpressionType.EQUAL,
                        clause.getProbe().toSymbolReference(),
                        clause.getIndex().toSymbolReference()));
            }

            print(indent, "- %sIndexJoin[%s]%s => [%s]",
                    node.getType().getJoinLabel(),
                    Joiner.on(" AND ").join(joinExpressions),
                    formatHash(node.getProbeHashSymbol(), node.getIndexHashSymbol()),
                    formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());
            node.getProbeSource().accept(this, indent + 1);
            node.getIndexSource().accept(this, indent + 1);

            return null;
        }

        @Override
        public Void visitLimit(LimitNode node, Integer indent)
        {
            print(indent, "- Limit%s[%s] => [%s]", node.isPartial() ? "Partial" : "", node.getCount(), formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());
            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitDistinctLimit(DistinctLimitNode node, Integer indent)
        {
            print(indent, "- DistinctLimit%s[%s]%s => [%s]",
                    node.isPartial() ? "Partial" : "",
                    node.getLimit(),
                    formatHash(node.getHashSymbol()),
                    formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());
            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitAggregation(AggregationNode node, Integer indent)
        {
            String type = "";
            if (node.getStep() != AggregationNode.Step.SINGLE) {
                type = format("(%s)", node.getStep().toString());
            }
            String key = "";
            if (!node.getGroupingKeys().isEmpty()) {
                key = node.getGroupingKeys().toString();
            }

            print(indent, "- Aggregate%s%s%s => [%s]", type, key, formatHash(node.getHashSymbol()), formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());

            for (Map.Entry<Symbol, FunctionCall> entry : node.getAggregations().entrySet()) {
                if (node.getMasks().containsKey(entry.getKey())) {
                    print(indent + 2, "%s := %s (mask = %s)", entry.getKey(), entry.getValue(), node.getMasks().get(entry.getKey()));
                }
                else {
                    print(indent + 2, "%s := %s", entry.getKey(), entry.getValue());
                }
            }

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitGroupId(GroupIdNode node, Integer indent)
        {
            // grouping sets are easier to understand in terms of inputs
            List<List<Symbol>> inputGroupingSetSymbols = node.getGroupingSets().stream()
                    .map(set -> set.stream()
                            .map(symbol -> node.getGroupingSetMappings().get(symbol))
                            .collect(Collectors.toList()))
                    .collect(Collectors.toList());

            print(indent, "- GroupId%s => [%s]", inputGroupingSetSymbols, formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());

            for (Map.Entry<Symbol, Symbol> mapping : node.getGroupingSetMappings().entrySet()) {
                print(indent + 2, "%s := %s", mapping.getKey(), mapping.getValue());
            }
            for (Map.Entry<Symbol, Symbol> argument : node.getArgumentMappings().entrySet()) {
                print(indent + 2, "%s := %s", argument.getKey(), argument.getValue());
            }

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitMarkDistinct(MarkDistinctNode node, Integer indent)
        {
            print(indent, "- MarkDistinct[distinct=%s marker=%s]%s => [%s]",
                    formatOutputs(node.getDistinctSymbols()),
                    node.getMarkerSymbol(),
                    formatHash(node.getHashSymbol()),
                    formatOutputs(node.getOutputSymbols()));

            printStats(indent + 2, node.getId());
            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitWindow(WindowNode node, Integer indent)
        {
            List<String> partitionBy = Lists.transform(node.getPartitionBy(), Functions.toStringFunction());

            List<String> orderBy = Lists.transform(node.getOrderBy(), input -> input + " " + node.getOrderings().get(input));

            List<String> args = new ArrayList<>();
            if (!partitionBy.isEmpty()) {
                List<Symbol> prePartitioned = node.getPartitionBy().stream()
                        .filter(node.getPrePartitionedInputs()::contains)
                        .collect(toImmutableList());

                List<Symbol> notPrePartitioned = node.getPartitionBy().stream()
                        .filter(column -> !node.getPrePartitionedInputs().contains(column))
                        .collect(toImmutableList());

                StringBuilder builder = new StringBuilder();
                if (!prePartitioned.isEmpty()) {
                    builder.append("<")
                            .append(Joiner.on(", ").join(prePartitioned))
                            .append(">");
                    if (!notPrePartitioned.isEmpty()) {
                        builder.append(", ");
                    }
                }
                if (!notPrePartitioned.isEmpty()) {
                    builder.append(Joiner.on(", ").join(notPrePartitioned));
                }
                args.add(format("partition by (%s)", builder));
            }
            if (!orderBy.isEmpty()) {
                args.add(format("order by (%s)", Stream.concat(
                        node.getOrderBy().stream()
                                .limit(node.getPreSortedOrderPrefix())
                                .map(symbol -> "<" + symbol + " " + node.getOrderings().get(symbol) + ">"),
                        node.getOrderBy().stream()
                                .skip(node.getPreSortedOrderPrefix())
                                .map(symbol -> symbol + " " + node.getOrderings().get(symbol)))
                        .collect(Collectors.joining(", "))));
            }

            print(indent, "- Window[%s]%s => [%s]", Joiner.on(", ").join(args), formatHash(node.getHashSymbol()), formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());

            for (Map.Entry<Symbol, WindowNode.Function> entry : node.getWindowFunctions().entrySet()) {
                FunctionCall call = entry.getValue().getFunctionCall();
                String frameInfo = call.getWindow()
                        .flatMap(Window::getFrame)
                        .map(PlanPrinter::formatFrame)
                        .orElse("");

                print(indent + 2, "%s := %s(%s) %s", entry.getKey(), call.getName(), Joiner.on(", ").join(call.getArguments()), frameInfo);
            }
            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitTopNRowNumber(TopNRowNumberNode node, Integer indent)
        {
            List<String> partitionBy = Lists.transform(node.getPartitionBy(), Functions.toStringFunction());

            List<String> orderBy = Lists.transform(node.getOrderBy(), input -> input + " " + node.getOrderings().get(input));

            List<String> args = new ArrayList<>();
            args.add(format("partition by (%s)", Joiner.on(", ").join(partitionBy)));
            args.add(format("order by (%s)", Joiner.on(", ").join(orderBy)));

            print(indent, "- TopNRowNumber[%s limit %s]%s => [%s]",
                    Joiner.on(", ").join(args),
                    node.getMaxRowCountPerPartition(),
                    formatHash(node.getHashSymbol()),
                    formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());

            print(indent + 2, "%s := %s", node.getRowNumberSymbol(), "row_number()");
            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitRowNumber(RowNumberNode node, Integer indent)
        {
            List<String> partitionBy = Lists.transform(node.getPartitionBy(), Functions.toStringFunction());
            List<String> args = new ArrayList<>();
            if (!partitionBy.isEmpty()) {
                args.add(format("partition by (%s)", Joiner.on(", ").join(partitionBy)));
            }

            if (node.getMaxRowCountPerPartition().isPresent()) {
                args.add(format("limit = %s", node.getMaxRowCountPerPartition().get()));
            }

            print(indent, "- RowNumber[%s]%s => [%s]", Joiner.on(", ").join(args), formatHash(node.getHashSymbol()), formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());

            print(indent + 2, "%s := %s", node.getRowNumberSymbol(), "row_number()");
            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitTableScan(TableScanNode node, Integer indent)
        {
            TableHandle table = node.getTable();
            print(indent, "- TableScan[%s, originalConstraint = %s] => [%s]", table, node.getOriginalConstraint(), formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());
            printTableScanInfo(node, indent);

            return null;
        }

        @Override
        public Void visitValues(ValuesNode node, Integer indent)
        {
            print(indent, "- Values => [%s]", formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());
            for (List<Expression> row : node.getRows()) {
                print(indent + 2, "(" + Joiner.on(", ").join(row) + ")");
            }
            return null;
        }

        @Override
        public Void visitFilter(FilterNode node, Integer indent)
        {
            return visitScanFilterAndProjectInfo(node.getId(), Optional.of(node), Optional.empty(), indent);
        }

        @Override
        public Void visitProject(ProjectNode node, Integer indent)
        {
            if (node.getSource() instanceof FilterNode) {
                return visitScanFilterAndProjectInfo(node.getId(), Optional.of((FilterNode) node.getSource()), Optional.of(node), indent);
            }

            return visitScanFilterAndProjectInfo(node.getId(), Optional.empty(), Optional.of(node), indent);
        }

        private Void visitScanFilterAndProjectInfo(
                PlanNodeId planNodeId,
                Optional<FilterNode> filterNode, Optional<ProjectNode> projectNode,
                int indent)
        {
            checkState(projectNode.isPresent() || filterNode.isPresent());

            PlanNode sourceNode;
            if (filterNode.isPresent()) {
                sourceNode = filterNode.get().getSource();
            }
            else {
                sourceNode = projectNode.get().getSource();
            }

            Optional<TableScanNode> scanNode;
            if (sourceNode instanceof TableScanNode) {
                scanNode = Optional.of((TableScanNode) sourceNode);
            }
            else {
                scanNode = Optional.empty();
            }

            String format = "[";
            String operatorName = "- ";
            List<Object> arguments = new LinkedList<>();

            if (scanNode.isPresent()) {
                operatorName += "Scan";
                format += "table = %s, originalConstraint = %s";
                if (filterNode.isPresent()) {
                    format += ", ";
                }
                TableHandle table = scanNode.get().getTable();
                arguments.add(table);
                arguments.add(scanNode.get().getOriginalConstraint());
            }

            if (filterNode.isPresent()) {
                operatorName += "Filter";
                format += "filterPredicate = %s";
                arguments.add(filterNode.get().getPredicate());
            }

            format += "] => [%s]";
            if (projectNode.isPresent()) {
                operatorName += "Project";
                arguments.add(formatOutputs(projectNode.get().getOutputSymbols()));
            }
            else {
                arguments.add(formatOutputs(filterNode.get().getOutputSymbols()));
            }

            format = operatorName + format;
            print(indent, format, arguments);
            printStats(indent + 2, planNodeId, true, true);

            if (projectNode.isPresent()) {
                printAssignments(projectNode.get().getAssignments(), indent + 2);
            }

            if (scanNode.isPresent()) {
                printTableScanInfo(scanNode.get(), indent);
                return null;
            }

            sourceNode.accept(this, indent + 1);
            return null;
        }

        private void printTableScanInfo(TableScanNode node, int indent)
        {
            TableHandle table = node.getTable();

            TupleDomain<ColumnHandle> predicate = node.getLayout()
                    .map(layoutHandle -> metadata.getLayout(session, layoutHandle))
                    .map(TableLayout::getPredicate)
                    .orElse(TupleDomain.all());

            if (node.getLayout().isPresent()) {
                // TODO: find a better way to do this
                ConnectorTableLayoutHandle layout = node.getLayout().get().getConnectorHandle();
                if (!table.getConnectorHandle().toString().equals(layout.toString())) {
                    print(indent + 2, "LAYOUT: %s", layout);
                }
            }

            if (predicate.isNone()) {
                print(indent + 2, ":: NONE");
            }
            else {
                // first, print output columns and their constraints
                for (Map.Entry<Symbol, ColumnHandle> assignment : node.getAssignments().entrySet()) {
                    ColumnHandle column = assignment.getValue();
                    print(indent + 2, "%s := %s", assignment.getKey(), column);
                    printConstraint(indent + 3, column, predicate);
                }

                // then, print constraints for columns that are not in the output
                if (!predicate.isAll()) {
                    Set<ColumnHandle> outputs = ImmutableSet.copyOf(node.getAssignments().values());

                    predicate.getDomains().get()
                            .entrySet().stream()
                            .filter(entry -> !outputs.contains(entry.getKey()))
                            .forEach(entry -> {
                                ColumnHandle column = entry.getKey();
                                print(indent + 2, "%s", column);
                                printConstraint(indent + 3, column, predicate);
                            });
                }
            }
        }

        @Override
        public Void visitUnnest(UnnestNode node, Integer indent)
        {
            print(indent, "- Unnest [replicate=%s, unnest=%s] => [%s]", formatOutputs(node.getReplicateSymbols()), formatOutputs(node.getUnnestSymbols().keySet()), formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitOutput(OutputNode node, Integer indent)
        {
            print(indent, "- Output[%s] => [%s]", Joiner.on(", ").join(node.getColumnNames()), formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());
            for (int i = 0; i < node.getColumnNames().size(); i++) {
                String name = node.getColumnNames().get(i);
                Symbol symbol = node.getOutputSymbols().get(i);
                if (!name.equals(symbol.toString())) {
                    print(indent + 2, "%s := %s", name, symbol);
                }
            }

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitTopN(TopNNode node, Integer indent)
        {
            Iterable<String> keys = Iterables.transform(node.getOrderBy(), input -> input + " " + node.getOrderings().get(input));

            print(indent, "- TopN[%s by (%s)] => [%s]", node.getCount(), Joiner.on(", ").join(keys), formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());
            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitSort(SortNode node, Integer indent)
        {
            Iterable<String> keys = Iterables.transform(node.getOrderBy(), input -> input + " " + node.getOrderings().get(input));

            print(indent, "- Sort[%s] => [%s]", Joiner.on(", ").join(keys), formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());
            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitRemoteSource(RemoteSourceNode node, Integer indent)
        {
            print(indent, "- RemoteSource[%s] => [%s]", Joiner.on(',').join(node.getSourceFragmentIds()), formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());

            return null;
        }

        @Override
        public Void visitUnion(UnionNode node, Integer indent)
        {
            print(indent, "- Union => [%s]", formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitIntersect(IntersectNode node, Integer indent)
        {
            print(indent, "- Intersect => [%s]", formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitExcept(ExceptNode node, Integer indent)
        {
            print(indent, "- Except => [%s]", formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitTableWriter(TableWriterNode node, Integer indent)
        {
            print(indent, "- TableWriter => [%s]", formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());
            for (int i = 0; i < node.getColumnNames().size(); i++) {
                String name = node.getColumnNames().get(i);
                Symbol symbol = node.getColumns().get(i);
                print(indent + 2, "%s := %s", name, symbol);
            }

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitTableFinish(TableFinishNode node, Integer indent)
        {
            print(indent, "- TableCommit[%s] => [%s]", node.getTarget(), formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitSample(SampleNode node, Integer indent)
        {
            print(indent, "- Sample[%s: %s] => [%s]", node.getSampleType(), node.getSampleRatio(), formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitExchange(ExchangeNode node, Integer indent)
        {
            if (node.getScope() == Scope.LOCAL) {
                print(indent, "- LocalExchange[%s%s]%s (%s) => %s",
                        node.getPartitioningScheme().getPartitioning().getHandle(),
                        node.getPartitioningScheme().isReplicateNulls() ? " - REPLICATE NULLS" : "",
                        formatHash(node.getPartitioningScheme().getHashColumn()),
                        Joiner.on(", ").join(node.getPartitioningScheme().getPartitioning().getArguments()),
                        formatOutputs(node.getOutputSymbols()));
            }
            else {
                print(indent, "- %sExchange[%s%s]%s => %s",
                        UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, node.getScope().toString()),
                        node.getType(),
                        node.getPartitioningScheme().isReplicateNulls() ? " - REPLICATE NULLS" : "",
                        formatHash(node.getPartitioningScheme().getHashColumn()),
                        formatOutputs(node.getOutputSymbols()));
            }
            printStats(indent + 2, node.getId());

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitDelete(DeleteNode node, Integer indent)
        {
            print(indent, "- Delete[%s] => [%s]", node.getTarget(), formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitMetadataDelete(MetadataDeleteNode node, Integer indent)
        {
            print(indent, "- MetadataDelete[%s] => [%s]", node.getTarget(), formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitEnforceSingleRow(EnforceSingleRowNode node, Integer indent)
        {
            print(indent, "- Scalar => [%s]", formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitAssignUniqueId(AssignUniqueId node, Integer indent)
        {
            print(indent, "- AssignUniqueId => [%s]", formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());

            return processChildren(node, indent + 1);
        }

        @Override
        public Void visitApply(ApplyNode node, Integer indent)
        {
            print(indent, "- Apply[%s] => [%s]", node.getCorrelation(), formatOutputs(node.getOutputSymbols()));
            printStats(indent + 2, node.getId());
            printAssignments(node.getSubqueryAssignments(), indent + 4);

            return processChildren(node, indent + 1);
        }

        @Override
        protected Void visitPlan(PlanNode node, Integer indent)
        {
            throw new UnsupportedOperationException("not yet implemented: " + node.getClass().getName());
        }

        private Void processChildren(PlanNode node, int indent)
        {
            for (PlanNode child : node.getSources()) {
                child.accept(this, indent);
            }

            return null;
        }

        private void printAssignments(Assignments assignments, int indent)
        {
            for (Map.Entry<Symbol, Expression> entry : assignments.getMap().entrySet()) {
                if (entry.getValue() instanceof SymbolReference && ((SymbolReference) entry.getValue()).getName().equals(entry.getKey().getName())) {
                    // skip identity assignments
                    continue;
                }
                print(indent, "%s := %s", entry.getKey(), entry.getValue());
            }
        }

        private String formatOutputs(Iterable<Symbol> symbols)
        {
            return Joiner.on(", ").join(Iterables.transform(symbols, input -> input + ":" + types.get(input).getDisplayName()));
        }

        private void printConstraint(int indent, ColumnHandle column, TupleDomain<ColumnHandle> constraint)
        {
            checkArgument(!constraint.isNone());
            Map<ColumnHandle, Domain> domains = constraint.getDomains().get();
            if (!constraint.isAll() && domains.containsKey(column)) {
                print(indent, ":: %s", formatDomain(simplifyDomain(domains.get(column))));
            }
        }

        private String formatDomain(Domain domain)
        {
            ImmutableList.Builder<String> parts = ImmutableList.builder();

            if (domain.isNullAllowed()) {
                parts.add("NULL");
            }

            Type type = domain.getType();

            domain.getValues().getValuesProcessor().consume(
                    ranges -> {
                        for (Range range : ranges.getOrderedRanges()) {
                            StringBuilder builder = new StringBuilder();
                            if (range.isSingleValue()) {
                                String value = castToVarchar(type, range.getSingleValue(), PlanPrinter.this.metadata, session);
                                builder.append('[').append(value).append(']');
                            }
                            else {
                                builder.append((range.getLow().getBound() == Marker.Bound.EXACTLY) ? '[' : '(');

                                if (range.getLow().isLowerUnbounded()) {
                                    builder.append("<min>");
                                }
                                else {
                                    builder.append(castToVarchar(type, range.getLow().getValue(), PlanPrinter.this.metadata, session));
                                }

                                builder.append(", ");

                                if (range.getHigh().isUpperUnbounded()) {
                                    builder.append("<max>");
                                }
                                else {
                                    builder.append(castToVarchar(type, range.getHigh().getValue(), PlanPrinter.this.metadata, session));
                                }

                                builder.append((range.getHigh().getBound() == Marker.Bound.EXACTLY) ? ']' : ')');
                            }
                            parts.add(builder.toString());
                        }
                    },
                    discreteValues -> discreteValues.getValues().stream()
                            .map(value -> castToVarchar(type, value, PlanPrinter.this.metadata, session))
                            .sorted() // Sort so the values will be printed in predictable order
                            .forEach(parts::add),
                    allOrNone -> {
                        if (allOrNone.isAll()) {
                            parts.add("ALL VALUES");
                        }
                    });

            return "[" + Joiner.on(", ").join(parts.build()) + "]";
        }
    }

    private static String formatHash(Optional<Symbol>... hashes)
    {
        List<Symbol> symbols = Arrays.stream(hashes)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(toList());

        if (symbols.isEmpty()) {
            return "";
        }

        return "[" + Joiner.on(", ").join(symbols) + "]";
    }

    private static String formatFrame(WindowFrame frame)
    {
        StringBuilder builder = new StringBuilder(frame.getType().toString());
        FrameBound start = frame.getStart();
        if (start.getValue().isPresent()) {
            builder.append(" ").append(start.getOriginalValue().get());
        }
        builder.append(" ").append(start.getType());

        Optional<FrameBound> end = frame.getEnd();
        if (end.isPresent()) {
            if (end.get().getOriginalValue().isPresent()) {
                builder.append(" ").append(end.get().getOriginalValue().get());
            }
            builder.append(" ").append(end.get().getType());
        }
        return builder.toString();
    }

    private static String castToVarchar(Type type, Object value, Metadata metadata, Session session)
    {
        if (value == null) {
            return "NULL";
        }

        Signature coercion = metadata.getFunctionRegistry().getCoercion(type, VARCHAR);

        try {
            Slice coerced = (Slice) new FunctionInvoker(metadata.getFunctionRegistry()).invoke(coercion, session.toConnectorSession(), value);
            return coerced.toStringUtf8();
        }
        catch (OperatorNotFoundException e) {
            return "<UNREPRESENTABLE VALUE>";
        }
        catch (Throwable throwable) {
            throw Throwables.propagate(throwable);
        }
    }

    private static class PlanNodeStats
    {
        private final PlanNodeId planNodeId;
        private final Duration wallTime;
        private final long inputPositions;
        private final DataSize inputDataSize;
        private final long outputPositions;
        private final DataSize outputDataSize;

        private PlanNodeStats(PlanNodeId planNodeId, Duration wallTime, long inputPositions, DataSize inputDataSize, long outputPositions, DataSize outputDataSize)
        {
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.wallTime = requireNonNull(wallTime, "wallTime is null");
            this.inputPositions = inputPositions;
            this.inputDataSize = requireNonNull(inputDataSize, "inputDataSize is null");
            this.outputPositions = outputPositions;
            this.outputDataSize = requireNonNull(outputDataSize, "outputDataSize is null");
        }

        public PlanNodeId getPlanNodeId()
        {
            return planNodeId;
        }

        public Duration getWallTime()
        {
            return wallTime;
        }

        public long getInputPositions()
        {
            return inputPositions;
        }

        public DataSize getInputDataSize()
        {
            return inputDataSize;
        }

        public long getOutputPositions()
        {
            return outputPositions;
        }

        public DataSize getOutputDataSize()
        {
            return outputDataSize;
        }

        public static PlanNodeStats merge(PlanNodeStats planNodeStats1, PlanNodeStats planNodeStats2)
        {
            checkArgument(planNodeStats1.getPlanNodeId().equals(planNodeStats2.getPlanNodeId()), "planNodeIds do not match. %s != %s", planNodeStats1.getPlanNodeId(), planNodeStats2.getPlanNodeId());

            long inputPositions = planNodeStats1.inputPositions + planNodeStats2.inputPositions;
            DataSize inputDataSize = succinctBytes(planNodeStats1.inputDataSize.toBytes() + planNodeStats2.inputDataSize.toBytes());

            long outputPositions = planNodeStats1.outputPositions + planNodeStats2.outputPositions;
            DataSize outputDataSize = succinctBytes(planNodeStats1.outputDataSize.toBytes() + planNodeStats2.outputDataSize.toBytes());

            return new PlanNodeStats(
                    planNodeStats1.getPlanNodeId(),
                    new Duration(planNodeStats1.getWallTime().toMillis() + planNodeStats2.getWallTime().toMillis(), MILLISECONDS),
                    inputPositions, inputDataSize,
                    outputPositions, outputDataSize);
        }
    }
}
