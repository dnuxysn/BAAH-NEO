# MaaFwApp PI parser used by the trial

Upstream revision: https://github.com/Aliothmoon/MaaFwApp/tree/c2449d8030644386e1a9e3ba7cb21037c2bcdbdf

The trial carries PiParser, ProjectDefinition, Diagnostics, UiText, OptionValue, and the two helpers and diagnostic resources they depend on. Diagnostics points to NEO's R class. OptionValue is an in-memory model in this trial and its serializer annotations are omitted. Upstream license is included here. NEO's UI, Android services and BAAH runtime remain the host; PI workflow and single-task entries run in native MaaFramework Tasker. The custom action starts the selected BAAH worker and returns its final outcome.
