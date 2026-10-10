package com.example.venue.placement.contract;

/**
 * 商业广告只读消费的 R6 仲裁合同。广告域只能依据明确的 R6 结果决定是否回填，
 * 不读取 placement 表或 Mapper；R6 不可用时必须保持商业回填关闭。
 */
public interface OwnedPlacementDecisionReader {
    ArbitrationResult readHomePrimary();

    enum Status {
        AVAILABLE,
        EMPTY,
        UNAVAILABLE
    }

    record ArbitrationResult(Status status) {
        public static ArbitrationResult available() {
            return new ArbitrationResult(Status.AVAILABLE);
        }

        public static ArbitrationResult empty() {
            return new ArbitrationResult(Status.EMPTY);
        }

        public static ArbitrationResult unavailable() {
            return new ArbitrationResult(Status.UNAVAILABLE);
        }
    }
}
