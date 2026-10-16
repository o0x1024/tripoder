# 独立验收未通过

设备result通过132次真实writer事件及26,508次调用，但当时独立verifier报`ART_AND_ELF_BTI`，未生成verification.json，不能视为完整验收通过。

原ART文件映射及保留的文件视图均没有`bt`；匿名发布页同样没有`bt`，近地址ELF有`bt`。Core正确保留原属性，错误来自验收器将PAC指令或邻近ELF的BTI属性当成源页必须启用BTI的证据。修订后将采集发布前maps/smaps，核对源页、保留文件视图和发布页的BTI准确一致，另要求新ELF启用BTI。不会追认本run为通过；需要新源码/构建和新runId重新验收。
