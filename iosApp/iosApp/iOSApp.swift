import SwiftUI
import TammyUI

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self)
    var appDelegate: AppDelegate

    @Environment(\.scenePhase)
    var scenePhase: ScenePhase

    var lifecycleHolder: LifeCycleHolder { appDelegate.lifecycleHolder }

    var body: some Scene {
        WindowGroup {
            MainView(lifecycle: lifecycleHolder.lifecycle)
                .onChange(of: scenePhase) { newPhase in
                    switch newPhase {
                        case .background: LifecycleRegistryExtKt.stop(lifecycleHolder.lifecycle)
                        case .inactive: LifecycleRegistryExtKt.pause(lifecycleHolder.lifecycle)
                        case .active: LifecycleRegistryExtKt.resume(lifecycleHolder.lifecycle)
                        @unknown default: break
                    }
                }
                .ignoresSafeArea(.all)
        }
    }
}
