# 独立验收未通过

设备ABI结果通过，但旧verifier将helper主机检查数固定为50。扩容增加负slot检查后实际为51，本次报HOST_HELPER_ORACLE，没有verification.json。

后续版本同步预期并增加63/64/2047三个slot的DEX独立解码核验，使用新构建和新runId重新验收，不追认本次为通过。
