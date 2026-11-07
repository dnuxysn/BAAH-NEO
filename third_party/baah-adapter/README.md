# BAAH-AOS 复用清单

适配层源自基于 MaaFwApp 的 BAAH-AOS。保留原工程 AGPL-3.0 许可证，不将派生 UI或 Python 适配代码声明为独立原创。

- `sources.json`：Python 适配资源的原始指纹。
- `ui-sources.json`：复用的配置编辑、预览动画、字体、通知展示、日志等源文件，以及修改说明。
- `notification-sources.json`：历史上临时复制的 11 个通知渠道及工具/模型指纹；409 已全部移除，改为 NEO 独立发送模块。

字体原生实现同样已移除；保留相同字号的独立配置。快捷菜单只保留 BAAH 专用新增展示和动画。

BAAH 定制复用部分修改主要为 namespace、NEO 数据/服务绑定与协程调度。`wrapper.py` 另增加 `runner_generation` 字段，避免旧轮次的延迟状态响应结束新任务。原始指纹保留用于追溯，工作树中的实际修改源码随项目提供。

新 Android 控制服务、虚拟屏/输入实现、前台服务生命周期、运行宿主和定时调度没有复制旧 MaaFwApp 宿主模块。

环境资产沿用旧工程清理后的 Ubuntu/Python rootfs 与 proot 组件。旧环境完整组件许可证审查尚未完成；这里不将已有审查限制改写为已通过。应用图标由 ChatGPT 生成，由维护者选定并提供。
