package com.offerchaser.worker.boss;

/**
 * 任务取消异常。
 * <p>
 * 只表示"本轮任务被用户或熔断逻辑取消"，不是程序故障。上层捕获后按正常结束处理，
 * 不要把取消记成 FAILED，也不要在取消后继续发起任何页面点击。
 */
public class RunCancelledException extends RuntimeException {

    public RunCancelledException(String message) {
        super(message);
    }
}
