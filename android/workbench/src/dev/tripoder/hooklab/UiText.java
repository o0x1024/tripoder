package dev.tripoder.hooklab;

import java.util.*;

/** What the user reads: engine codes and states become sentences that say what happened and what to do next. Raw codes stay available as details. */
final class UiText {
    private static final Map<String,String> CODES=new LinkedHashMap<>();
    static{
        String[][] rows={
            {"ENGINE_NOT_CONNECTED","运行引擎未连接，请稍后重试"},
            {"ENGINE_START_REJECTED","系统拒绝启动运行引擎，请重新打开 Tripoder"},
            {"ENGINE_BIND_REJECTED","无法连接运行引擎，请重新打开 Tripoder"},
            {"ENGINE_NULL_BINDING","无法连接运行引擎，请重新打开 Tripoder"},
            {"ENGINE_HANDSHAKE_REJECTED","运行引擎身份校验失败，请重新安装 Tripoder"},
            {"AGENT_SESSION_ACTIVE_REVOKE_FIRST","外部 Agent 会话正在控制这个项目，请先在「外部 Agent」中撤销会话"},
            {"AGENT_PAIR_REQUEST_STALE","配对请求已失效，请在桌面端重新发起配对"},
            {"AGENT_PAIR_REQUEST_EXPIRED","配对请求已过期，请在桌面端重新发起配对"},
            {"AGENT_ENDPOINT_CLOSED","外部控制入口已关闭"},
            {"LAUNCH_TARGET_REJECTED","上一次启动没有正常结束，需要先关闭旧的启动入口"},
            {"LAUNCH_NOT_DISPATCHED","应用界面没有打开，请重新启动"},
            {"LAUNCH_ENTRY_NOT_ENTERED","系统没有打开应用界面，请回到 Tripoder 后重新启动"},
            {"LAUNCH_BINDING_STALE","项目的运行目标已失效，请重新选择运行目标"},
            {"HOOK_PROJECT_BINDING_STALE","项目的运行目标已失效，请重新选择运行目标"},
            {"PROJECT_NOT_BOUND","项目还没有运行目标，请先选择一个应用"},
            {"PROJECT_BINDING_CONFLICT","运行目标刚被修改过，请刷新后重试"},
            {"PROJECT_BINDING_STALE","应用已被移除或更新，请重新选择运行目标"},
            {"PROJECT_PACKAGE_CHANGE_UNSUPPORTED","每个应用只有一个项目，只能选择这个应用的运行实例"},
            {"APPLICATION_PROJECT_ALREADY_EXISTS","这个应用已有唯一项目，请刷新后打开分析"},
            {"INSTALL_GENERATION_CONFLICT","应用安装已变化，请刷新并重新选择目标"},
            {"SOURCE_REVISION_CONFLICT","脚本已被其他入口修改，请重新读取后再保存"},
            {"PROJECT_HOOK_CONTEXT_IN_USE","这个项目的脚本仍在运行，请先停用脚本或停止应用"},
            {"HOOK_PROCESS_STALE","所选进程已经退出，请重新选择运行进程"},
            {"HOOK_PROJECT_PROCESS_MISMATCH","所选进程不属于这个项目的应用，请重新选择"},
            {"STALE_INSTALL","应用已更新或重新安装，请刷新后重试"},
            {"INSTALL_GENERATION_CONFLICT","应用已更新或重新安装，请刷新后重试"},
            {"INSTALL_CORRUPT","安装包校验失败，请重新添加这个应用"},
            {"APPLICATION_HAS_NO_LAUNCHER","这个应用没有可以直接打开的主界面"},
            {"APPLICATION_NOT_INSTALLED","应用已不在 Tripoder 中，请刷新"},
            {"PACKAGE_NOT_IN_SPACE","应用不在这个空间中，请刷新"},
            {"SPACE_NOT_EMPTY","空间中仍有应用或项目"},
            {"CATALOG_PAGE_REQUIRED","应用或项目数量超过了当前支持的上限"},
            {"CONTROL_COMMAND_UNSUPPORTED","工作台与运行引擎版本不一致，请重新安装 Tripoder"},
            {"CONTROL_FIELDS_UNSUPPORTED","工作台与运行引擎版本不一致，请重新安装 Tripoder"},
            {"OPERATION_NOT_FOUND","找不到这项操作的记录"},
            {"OPERATION_QUERY_REQUIRED","同一操作已经在处理，请稍后刷新结果"},
            {"CANCELED_BEFORE_ACCEPT","操作已取消"},
            {"UNINSTALL_REJECTED","移除没有完成，应用和数据保持不变"},
            {"DELETE_APPLICATION_REJECTED","删除没有完成，应用和数据保持不变"},
            {"DELETE_APPLICATION_PARTIAL","只删除了一部分，请再次删除以完成"},
            {"DELETE_SPACE_REJECTED","空间没有删除，其中的应用和数据保持不变"},
            {"DELETE_SPACE_PARTIAL","空间只清理了一部分，请再次删除空间以完成"},
            {"SPACE_NOT_FOUND","空间已不存在，请刷新"},
            {"DELETE_PROJECT_REJECTED","项目没有删除"},
        };
        for(String[] row:rows)CODES.put(row[0],row[1]);
    }
    /** Sentence for a failure; never the exception class name. */
    static String error(Throwable error){
        if(error==null)return "操作没有完成";
        if(error instanceof android.os.DeadObjectException)return "与运行引擎的连接已断开，正在重新连接";
        String message=error.getMessage();
        if(message==null||message.isBlank())return "操作没有完成（"+error.getClass().getSimpleName()+"）";
        return detail(message);
    }
    /** Same mapping for a stored operation detail or an engine message. Already-readable text passes through. */
    static String detail(String message){
        if(message==null||message.isBlank())return "没有更多说明";
        String code=message.trim();int colon=code.indexOf(':');String head=colon<0?code:code.substring(0,colon);
        String known=CODES.get(head);
        if(known==null&&head.endsWith("_ALREADY_ACCEPTED_QUERY_REQUIRED"))known="同一操作已经在处理，请稍后刷新结果";
        if(known!=null){
            // A wrapped code ("DELETE_PROJECT_REJECTED:PROJECT_HOOK_CONTEXT_IN_USE") explains itself best through its cause.
            // A partial deletion keeps its own sentence: what matters is that repeating it finishes the job.
            if(colon>0&&!head.endsWith("_PARTIAL")){String cause=code.substring(colon+1).trim();int next=cause.indexOf(':');String causeHead=next<0?cause:cause.substring(0,next);if(CODES.containsKey(causeHead))return CODES.get(causeHead);}
            return known;
        }
        if(head.matches("[A-Z][A-Z0-9_]{2,}"))return "操作没有完成（"+head+"）";
        return code;
    }
    /** Hook state as a person would describe it. */
    static String hookState(String state){
        switch(state==null?"":state){
            case "IDLE":return "未部署";
            case "OBSERVED":return "已观察目标";
            case "PREPARED":return "检查通过，等待激活";
            case "ACTIVE":return "运行中";
            case "DRAINING":return "正在停用";
            case "DISABLED":return "已停用";
            default:return "状态未确认";
        }
    }
    static String agentState(String state){
        switch(state==null?"":state){
            case "OPEN":return "已授权";
            case "CLOSED":return "已结束";
            default:return state==null||state.isEmpty()?"未知":state;
        }
    }
    static String duration(long ms){long s=Math.max(0,ms/1000);return s<60?s+" 秒":"约 "+((s+59)/60)+" 分钟";}
    private UiText(){}
}
