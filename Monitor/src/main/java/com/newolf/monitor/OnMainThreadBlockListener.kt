package com.newolf.monitor

/**
 * 主线程耗时超限回调接口
 */
interface OnMainThreadBlockListener {
    /**
     * 当主线程耗时超过某个阈值时回调
     *
     * @param blockInfo 包含耗时详情的信息
     */
    fun onBlock(blockInfo: MainThreadBlockInfo)
}