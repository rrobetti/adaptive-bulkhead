package io.github.rrobetti.adaptivebulkhead;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BorrowingAllocator {
    private static final Comparator<BulkheadNode> BORROWER_ORDER = Comparator
            .comparing((BulkheadNode node) -> node.policy().priority())
            .thenComparing(BulkheadNode::qualifiedName);

    private static final Comparator<BulkheadNode> DONOR_ORDER = Comparator
            .comparing((BulkheadNode node) -> node.policy().priority(), Comparator.reverseOrder())
            .thenComparing(BulkheadNode::qualifiedName);

    private BorrowingAllocator() {
    }

    static Map<BulkheadNode, Allocation> allocate(
            BulkheadNode parent,
            BulkheadNode requestedChild,
            boolean directRequestedAtParent
    ) {
        Map<BulkheadNode, ChildState> childStates = new HashMap<>();
        List<BulkheadNode> children = parent.children();
        int parentCapacity = Math.max(0, Math.min(parent.policy().maxConcurrency(), parent.runtimeState().effectiveLimit.get()));
        int directActive = Math.max(0, parent.directActive() + (directRequestedAtParent ? 1 : 0));
        int capacityForChildren = Math.max(0, parentCapacity - directActive);
        int totalGuaranteed = 0;
        int totalLendable = 0;

        for (BulkheadNode child : children) {
            int demand = Math.min(child.policy().maxConcurrency(), child.runtimeState().active.get() + (child == requestedChild ? 1 : 0));
            int reserved = Math.min(demand, child.policy().guaranteedConcurrency());
            int lendable = Math.max(0, child.policy().guaranteedConcurrency() - Math.max(reserved, child.policy().minimumRetainedCapacity()));
            int need = Math.max(0, demand - reserved);
            int borrowCap = Math.min(child.policy().maximumBorrow(), child.policy().maxConcurrency() - child.policy().guaranteedConcurrency());
            childStates.put(child, new ChildState(demand, reserved, need, lendable, borrowCap));
            totalGuaranteed += child.policy().guaranteedConcurrency();
            totalLendable += lendable;
        }

        int slack = Math.max(0, capacityForChildren - totalGuaranteed);
        int borrowPool = slack + totalLendable;
        Map<BulkheadNode, Integer> borrowed = new HashMap<>();
        List<BulkheadNode> orderedBorrowers = new ArrayList<>(children);
        orderedBorrowers.sort(BORROWER_ORDER);

        for (Priority priority : Priority.values()) {
            boolean progressed = true;
            while (borrowPool > 0 && progressed) {
                progressed = false;
                for (BulkheadNode child : orderedBorrowers) {
                    if (child.policy().priority() != priority) {
                        continue;
                    }
                    ChildState state = childStates.get(child);
                    int allocated = borrowed.getOrDefault(child, 0);
                    for (int weightStep = 0; weightStep < child.policy().weight() && borrowPool > 0; weightStep++) {
                        if (allocated >= state.borrowCap || allocated >= state.need) {
                            break;
                        }
                        borrowed.put(child, allocated + 1);
                        allocated++;
                        borrowPool--;
                        progressed = true;
                    }
                }
            }
        }

        int donorBackedBorrow = Math.max(0, borrowed.values().stream().mapToInt(Integer::intValue).sum() - slack);
        Map<BulkheadNode, Integer> lent = new HashMap<>();
        List<BulkheadNode> donors = new ArrayList<>(children);
        donors.sort(DONOR_ORDER);
        for (BulkheadNode donor : donors) {
            if (donorBackedBorrow == 0) {
                break;
            }
            ChildState state = childStates.get(donor);
            int contribution = Math.min(state.lendable, donorBackedBorrow);
            if (contribution > 0) {
                lent.put(donor, contribution);
                donorBackedBorrow -= contribution;
            }
        }

        Map<BulkheadNode, Allocation> allocations = new HashMap<>();
        for (BulkheadNode child : children) {
            ChildState state = childStates.get(child);
            int effectiveLimit = state.reserved + borrowed.getOrDefault(child, 0);
            int active = child.runtimeState().active.get();
            int borrowedCapacity = Math.max(0, active - child.policy().guaranteedConcurrency());
            allocations.put(child, new Allocation(
                    effectiveLimit,
                    borrowedCapacity,
                    lent.getOrDefault(child, 0)
            ));
        }
        return allocations;
    }

    record Allocation(int effectiveLimit, int borrowedCapacity, int lentCapacity) {
    }

    private record ChildState(int demand, int reserved, int need, int lendable, int borrowCap) {
    }
}
