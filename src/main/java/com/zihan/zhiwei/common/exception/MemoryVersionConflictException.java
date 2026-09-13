package com.zihan.zhiwei.common.exception;

import lombok.Getter;

@Getter
public class MemoryVersionConflictException extends RuntimeException {
    private final long expectedVersion;

    public MemoryVersionConflictException(long expectedVersion) {
        super("memory version conflict; expected version " + expectedVersion);
        this.expectedVersion = expectedVersion;
    }
}
