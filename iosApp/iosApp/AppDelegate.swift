import Foundation
import SwiftUI
import TeleCryptUI

class AppDelegate: NSObject, UIApplicationDelegate {
    let lifecycleHolder: LifeCycleHolder = LifeCycleHolder()

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        // APNs token registration is handled by the Kotlin framework
        // (ApnsPushNotificationProvider.UIApplicationDelegate, registered via
        // addApnsPushNotificationProvider in src/iosMain/.../main.kt).
        // The 3.x PushKt.setNotificationToken export was removed in the 4.x migration.
    }

    func application(
            _ application: UIApplication,
            didReceiveRemoteNotification userInfo: [AnyHashable : Any],
            fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        // Incoming-push handling is done by the Kotlin framework
        // (ApnsPushNotificationProvider.UIApplicationDelegate, registered via
        // addApnsPushNotificationProvider in src/iosMain/.../main.kt).
        // The 3.x PushKt.handleNotification export was removed in the 4.x migration.
        completionHandler(.noData)
    }
}
