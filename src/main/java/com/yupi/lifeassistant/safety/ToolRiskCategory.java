package com.yupi.lifeassistant.safety;

/** 工具风险分类，从低到高排列。UNKNOWN 类型会被保守策略拒绝或询问。 */
public enum ToolRiskCategory {
    /** 只读工具：读取笔记、搜索记忆、查看技能等，无副作用 */
    READ_ONLY,
    /** 纯计算工具：排日程、制定餐食计划等，仅做本地计算无 I/O */
    COMPUTE_ONLY,
    /** 文件编辑工具：写入或追加 LifeNote */
    FILE_EDIT,
    /** 记忆写入工具：向 core/archival/shared memory 写入或替换数据 */
    MEMORY_WRITE,
    /** 委托工具：将任务分发给其他 Agent */
    DELEGATION,
    /** 代码执行工具：在沙箱中运行用户提供的代码片段 */
    CODE_EXECUTION,
    /** 终止工具：结束 Agent 会话，无外部副作用 */
    TERMINATE,
    /** 未分类工具：默认保守处理，在多数模式下会被询问或拒绝 */
    UNKNOWN
}
