import SwiftUI
import TeleCryptUI

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
                // URL opening is handled by the Kotlin framework
                // (UrlHandlingUIWindowSceneDelegate, registered by startMultiMessenger).
                // The 3.x StartMessengerKt.handleUrl export was removed in the 4.x migration.
                .ignoresSafeArea(.all)
        }
    }
}
