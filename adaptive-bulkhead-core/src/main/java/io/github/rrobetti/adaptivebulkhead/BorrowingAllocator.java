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
        Map<BulkheadNode, Integer> borrowed = new HashMap<>();
        Map<BulkheadNode, Integer> lent = new HashMap<>();
        Map<BulkheadNode, Integer> donorRemaining = new HashMap<>();
        for (BulkheadNode child : children) {
            donorRemaining.put(child, childStates.get(child).lendable);
        }
        List<BulkheadNode> orderedBorrowers = new ArrayList<>(children);
        orderedBorrowers.sort(BORROWER_ORDER);
        List<BulkheadNode> donors = new ArrayList<>(children);
        donors.sort(DONOR_ORDER);

        for (Priority priority : Priority.values()) {
            boolean progressed = true;
            while ((slack > 0 || totalLendable > 0) && progressed) {
                progressed = false;
                for (BulkheadNode child : orderedBorrowers) {
                    if (child.policy().priority() != priority) {
                        continue;
                    }
                    ChildState state = childStates.get(child);
                    int allocated = borrowed.getOrDefault(child, 0);
                    for (int weightStep = 0; weightStep < child.policy().weight(); weightStep++) {
                        if (allocated >= state.borrowCap || allocated >= state.need) {
                            break;
                        }
                        if (slack > 0) {
                            borrowed.put(child, allocated + 1);
                            allocated++;
                            slack--;
                            progressed = true;
                            continue;
                        }
                        BulkheadNode donor = nextDonor(child, donors, donorRemaining);
                        if (donor == null) {
                            break;
                        }
                        borrowed.put(child, allocated + 1);
                        allocated++;
                        donorRemaining.put(donor, donorRemaining.get(donor) - 1);
                        lent.merge(donor, 1, Integer::sum);
                        totalLendable--;
                        progressed = true;
                    }
                }
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

    private static BulkheadNode nextDonor(
            BulkheadNode borrower,
            List<BulkheadNode> donors,
            Map<BulkheadNode, Integer> donorRemaining
    ) {
        for (BulkheadNode donor : donors) {
            if (borrower == donor || donorRemaining.getOrDefault(donor, 0) <= 0) {
                continue;
            }
            if (canBorrowFrom(borrower, donor)) {
                return donor;
            }
        }
        return null;
    }

    private static boolean canBorrowFrom(BulkheadNode borrower, BulkheadNode donor) {
        return switch (borrower.policy().borrowDirection()) {
            case ANY -> true;
            case HIGHER_PRIORITY_ONLY -> borrower.policy().priority().compareTo(donor.policy().priority()) < 0;
        };
    }

    private record ChildState(int demand, int reserved, int need, int lendable, int borrowCap) {
    }
}
