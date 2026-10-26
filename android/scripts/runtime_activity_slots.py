"""Deterministic real Android task profiles and alias entries for the independent runtime."""
from pathlib import Path

AFFINITIES = 8
ALIASES = 2
ALIAS_COMPONENTS = 2
MODES = (('standard', 16), ('singleTop', 16), ('singleTask', 8), ('singleInstance', 8), ('singleInstancePerTask', 8))


def generate_activity_slots(build: Path, manifest: Path, class_prefix: str):
    directory = build / 'activity-slots'
    directory.mkdir(exist_ok=True)
    sources, entries = [], []
    if class_prefix not in ('TaskP','SelfHookTaskP'):
        raise ValueError('ACTIVITY_POOL_IDENTITY_UNSUPPORTED')
    config=directory/'RuntimeComponentBuild.java'
    config.write_text('package dev.tripoder.runtime; final class RuntimeComponentBuild { static final String ACTIVITY_POOL_PREFIX="'+class_prefix+'"; }\n')
    sources.append(config)
    # Host package is part of the affinity namespace; guest package/space are leased later.
    import xml.etree.ElementTree as ET
    package = ET.fromstring(manifest.read_text()).attrib['package']
    for process in range(8):
        name = f'{class_prefix}{process}'
        source = directory / (name + '.java')
        classes = []
        for affinity in range(AFFINITIES + 1):
            physical_affinity = '' if affinity == AFFINITIES else f'{package}.runtime.affinity{affinity}'
            for mode, (launch, capacity) in enumerate(MODES):
                for reparent in ([False, True] if mode < 2 else [False]):
                    for component in range(capacity):
                        child = f'F{affinity}M{mode}C{component}' + ('R1' if reparent else '')
                        canonical = f'dev.tripoder.runtime.generated.{name}${child}'
                        classes.append(f'    public static final class {child} extends android.app.Activity {{}}')
                        entries.append(f'        <activity android:name="{canonical}" android:exported="false" android:process=":slot{process}" android:launchMode="{launch}" android:taskAffinity="{physical_affinity}" android:allowTaskReparenting="{str(reparent).lower()}"/>')
                        for alias in range(ALIASES if component < ALIAS_COMPONENTS else 0):
                            entries.append(f'        <activity-alias android:name="{canonical}E{alias}" android:targetActivity="{canonical}" android:exported="false"/>')
        source.write_text('package dev.tripoder.runtime.generated;\npublic final class ' + name + ' {\n' + '\n'.join(classes) + '\n}\n')
        sources.append(source)
    output = directory / 'AndroidManifest.xml'
    text = manifest.read_text()
    if text.count('<!-- ACTIVITY_COMPONENT_POOL -->') != 1:
        raise ValueError('ACTIVITY_COMPONENT_POOL_MARKER_REQUIRED')
    generated = text.replace('<!-- ACTIVITY_COMPONENT_POOL -->', '\n'.join(entries))
    # Platform manifest parser limits (AOSP Element.java). Count the entire
    # application, including manually declared probe / controller components.
    application = ET.fromstring(generated).find('application')
    for tag, limit in {'activity': 30000, 'activity-alias': 4000, 'service': 1000,
                       'receiver': 1000, 'provider': 8000}.items():
        count = len(application.findall(tag))
        if count > limit:
            raise ValueError(f'MANIFEST_COMPONENT_BUDGET:{tag}:{count}>{limit}')
    output.write_text(generated)
    return sources, output
