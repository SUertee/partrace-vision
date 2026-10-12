package com.anker.esiotkit.sample.demo.analyzer

import com.anker.esiotkit.sample.demo.model.SafetyAnalysisResult

/** 业务分析层：输入用户查询，输出结论（方案1/方案2） */
interface ISafetyAnalyzer {
  suspend fun analyze(query: String): SafetyAnalysisResult
}
