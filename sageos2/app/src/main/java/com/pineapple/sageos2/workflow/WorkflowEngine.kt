package com.pineapple.sageos2.workflow

interface WorkflowEngine {
    fun launch(turnId: Long, workflowId: String)
}
