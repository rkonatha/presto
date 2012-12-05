package com.facebook.presto.sql.planner;

import com.facebook.presto.metadata.Node;
import com.facebook.presto.metadata.NodeManager;
import com.facebook.presto.server.TableScanPlanFragmentSource;
import com.facebook.presto.split.Split;
import com.facebook.presto.split.SplitAssignments;
import com.facebook.presto.split.SplitManager;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.ExchangeNode;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.JoinNode;
import com.facebook.presto.sql.planner.plan.LimitNode;
import com.facebook.presto.sql.planner.plan.OutputNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.planner.plan.TopNNode;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;

import static com.google.common.collect.Iterables.transform;

public class DistributedExecutionPlanner
{
    private final NodeManager nodeManager;
    private final SplitManager splitManager;
    private final Random random = new Random();

    @Inject
    public DistributedExecutionPlanner(NodeManager nodeManager, SplitManager splitManager)
    {
        this.nodeManager = nodeManager;
        this.splitManager = splitManager;
    }

    public Stage plan(SubPlan root)
    {
        PlanFragment currentFragment = root.getFragment();

        // get partitions for this fragment
        List<Partition> partitions;
        if (currentFragment.isPartitioned()) {
            // partitioned plan is based on an underlying table scan or distributed aggregation
            partitions = getPartitions(currentFragment.getRoot());
        }
        else {
            // create a single partition on a random node for this fragment
            ArrayList<Node> nodes = new ArrayList<>(nodeManager.getActiveNodes());
            Preconditions.checkState(!nodes.isEmpty(), "Cluster does not have any active nodes");
            Collections.shuffle(nodes, random);
            Node node = nodes.get(0);
            partitions = ImmutableList.of(new Partition(node, ImmutableList.<PlanFragmentSource>of()));
        }

        // create child stages
        ImmutableList.Builder<Stage> dependencies = ImmutableList.builder();
        for (SubPlan childPlan : root.getChildren()) {
            Stage dependency = plan(childPlan);
            dependencies.add(dependency);
        }

        return new Stage(currentFragment, partitions, dependencies.build());
    }


    private List<Partition> getPartitions(PlanNode plan)
    {
        if (plan instanceof TableScanNode) {
            final TableScanNode tableScan = (TableScanNode) plan;

            // get splits for table
            Iterable<SplitAssignments> splitAssignments = splitManager.getSplitAssignments(tableScan.getTable());

            // divide splits amongst the nodes
            Multimap<Node, Split> nodeSplits = SplitAssignments.randomNodeAssignment(random, splitAssignments);

            // create a partition for each node
            ImmutableList.Builder<Partition> partitions = ImmutableList.builder();
            for (Entry<Node, Collection<Split>> entry : nodeSplits.asMap().entrySet()) {
                List<PlanFragmentSource> sources = ImmutableList.copyOf(transform(entry.getValue(), new Function<Split, PlanFragmentSource>()
                {
                    @Override
                    public PlanFragmentSource apply(Split split)
                    {
                        return new TableScanPlanFragmentSource(split);
                    }
                }));
                partitions.add(new Partition(entry.getKey(), sources));
            }
            return partitions.build();
        }
        else if (plan instanceof ExchangeNode) {
            // exchange node is not partitioned
            return ImmutableList.of();
        }
        else if (plan instanceof JoinNode) {
            JoinNode joinNode = (JoinNode) plan;
            List<Partition> leftPartitions = getPartitions(joinNode.getLeft());
            List<Partition> rightPartitions = getPartitions(joinNode.getRight());
            if (!leftPartitions.isEmpty() && !rightPartitions.isEmpty()) {
                throw new IllegalArgumentException("Both left and right join nodes are partitioned"); // TODO: "partitioned" may not be the right term
            }
            if (!leftPartitions.isEmpty()) {
                return leftPartitions;
            }
            else {
                return rightPartitions;
            }
        }
        else if (plan instanceof ProjectNode) {
            return getPartitions(((ProjectNode) plan).getSource());
        }
        else if (plan instanceof FilterNode) {
            return getPartitions(((FilterNode) plan).getSource());
        }
        else if (plan instanceof OutputNode) {
            return getPartitions(((OutputNode) plan).getSource());
        }
        else if (plan instanceof AggregationNode) {
            return getPartitions(((AggregationNode) plan).getSource());
        }
        else if (plan instanceof LimitNode) {
            return getPartitions(((LimitNode) plan).getSource());
        }
        else if (plan instanceof TopNNode) {
            return getPartitions(((TopNNode) plan).getSource());
        }
        else {
            throw new UnsupportedOperationException("not yet implemented: " + plan.getClass().getName());
        }
    }
}