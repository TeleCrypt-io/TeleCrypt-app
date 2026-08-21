import Foundation
import TeleCryptUI

class LifeCycleHolder : ObservableObject {
    let lifecycle: LifecycleRegistry

    init() {
        lifecycle = LifecycleRegistryKt.LifecycleRegistry()
        LifecycleRegistryExtKt.create(lifecycle)
    }

    deinit {
        // Destroy the Lifecycle before it is deallocated
        LifecycleRegistryExtKt.destroy(lifecycle)
    }
}
