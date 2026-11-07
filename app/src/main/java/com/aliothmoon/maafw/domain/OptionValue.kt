package com.aliothmoon.maafw.domain

sealed interface OptionValue {

    data class SingleCase(val case: String) : OptionValue

    data class MultipleCases(val cases: List<String>) : OptionValue

    data class Inputs(
        val values: Map<String, String>,
        /**
         * password 字段名：内存里照样是明文，落盘时由 UserConfigurationSerializer 加密进 [sealed]。
         * 按 PI 定义补标（withPasswordFieldsMarked），只增不减——PI 撤掉 password 后已加密的值也不回落明文
         */
        val secretFields: Set<String> = emptySet(),
        /** 只在落盘形态里有值：password 字段的密文；读回时解密进 [values] 并清空 */
        val sealed: Map<String, String> = emptyMap(),
    ) : OptionValue {
        override fun toString(): String =
            "Inputs(values=${values.mapValues { (k, v) -> if (k in secretFields) SECRET_MASK else v }}, " +
                "secretFields=$secretFields)"
    }
}
