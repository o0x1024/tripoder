// Public domains map to stable internal operations. Read/write groups never mix.
export const groups = {
  runtime_query: {
    description:'Read global runtime capability/status, resources, spaces or application catalogs. No implicit target selection.',
    actions:{capabilities:'runtime_capabilities',status:'runtime_status',resources:'runtime_resources',spaces:'spaces_list',applications:'applications_list',device_applications:'device_applications_list'}
  },
  application_query: {
    description:'Read installations, live process targets, exact process details, launch entries/recovery or upload status. Each action requires its own explicit identity.',
    actions:{targets:'targets_list',process:'process_get',launch_targets:'application_launch_targets',launch_entries:'launch_entries_list',launch_recovery:'launch_recovery_get',import_status:'application_import_status'}
  },
  application_manage:{description:'Modify an exact installation: import a device package, set stopped runtime mode or reverify APK bytes. Does not launch or remove applications.',actions:{import_device:'application_import_device',set_mode:'application_mode_set',reverify:'application_reverify'}},
  space_manage:{description:'Create or rename a runtime space. Deletion is the separate space_delete lifecycle tool.',actions:{create:'space_create',rename:'space_rename'}},
  project_query:{description:'Read application project source or retained source archives. Does not deploy Hooks.',actions:{get:'project_get',archives:'project_archives_list',archive:'project_archive_read'}},
  project_update:{description:'Save or restore project source with expected SHA/CAS; never activates Hooks.',actions:{script:'project_script_put',selectors:'project_selectors_put',restore:'project_archive_restore',accept_migration:'project_migration_accept'}},
  launch_manage:{description:'Close a stale launch entry or abandon a prepared entry using its original identity. Does not replay a launch.',actions:{close:'launch_close',abandon:'launch_abandon'}},
  probes_query:{description:'Read installation startup probe configuration or exact live-process probe status.',actions:{configuration:'probes_get',status:'probe_status'}},
  probes_control:{description:'Configure startup probes for new processes with CAS, or disable probes in an exact current process. Does not disable user Hooks.',actions:{configure:'probes_configure',stop:'probes_stop'}},
  code_query:{description:'Inspect discovered loaders/runtime DEX, APK DEX classes or native exports without initialization. runtime_class requires an explicit loader; apk_class inspects the APK view.',actions:{loaders:'classloaders_list',dexes:'dex_list',runtime_class:'class_inspect',apk_classes:'dex_search_classes',apk_class:'dex_inspect_class',native_exports:'native_exports_search'}},
  artifact_query:{description:'Read persisted COMPLETE code artifacts by process generation. Binary reads are bounded; prefer file_pull for computer downloads.',actions:{list:'artifacts_list',read:'artifact_read'}},
  events_query:{description:'Read bounded runtime/probe, Hook telemetry or captured HTTP traffic. Cursors belong to the selected stream and process.',actions:{runtime:'runtime_events',hook:'hook_events',network:'network_traffic_get'}},
  process_inspect:{description:'Read Hook status/modules, crash diagnostics, manifest, storage audit or UI hierarchy of an exact live process. Does not mutate or activate anything.',actions:{hook_status:'hook_status',modules:'hook_modules',diagnostics:'crash_diagnostics_get',manifest:'manifest_inspect',storage:'storage_inspect',ui_tree:'ui_dump_hierarchy'}},
  application_upload:{description:'Stage and install original APK bytes. begin creates an upload; write uses bounded exact offsets; commit verifies before installing; cleanup only releases the identified staging area.',actions:{begin:'application_upload_begin',write:'application_upload_write',commit:'application_upload_commit',cleanup:'application_import_cleanup'}},
  file_query:{description:'Inspect phone paths/directories or read a prepared export. stat/list use source; export_status/manifest/read use exportId. Bulk read is intended for local receivers, not model context.',actions:{stat:'file_stat',list:'files_list',export_status:'file_export_status',manifest:'file_export_manifest',read:'file_export_read'}},
  file_export:{description:'Prepare an immutable export, cancel its preparation or release only its staged bytes. Source files are never removed.',actions:{begin:'file_export_begin',cancel:'file_export_cancel',release:'file_export_release'}},
  file_pull:{description:'Computer-side stdio download control: start/resume with the same operationId and arguments, or cancel to pause. Query progress with file_pull_status. HTTP clients use Sentinel mcp_file_pull.',actions:{start:'file_pull',cancel:'file_pull_cancel'}}
};
export const synonyms={projects_list:'applications_list'};
