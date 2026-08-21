import Foundation
import SwiftUI
import TeleCryptUI

class AppDelegate: NSObject, UIApplicationDelegate {
    let lifecycleHolder: LifeCycleHolder = LifeCycleHolder()

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let token = deviceToken.map { data in String(format: "%02.2hhx", data) }.joined()
        PushKt.setNotificationToken(token: token)
    }

    func application(
            _ application: UIApplication,
            didReceiveRemoteNotification userInfo: [AnyHashable : Any],
            fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        guard let userId = userInfo["user_id"] as? String else {
            print("PushNotification: Missing user_id")
            completionHandler(.failed)
            return
        }

        guard let roomId = userInfo["room_id"] as? String else {
            print("PushNotification: Missing room_id")
            completionHandler(.failed)
            return
        }

        guard let eventId = userInfo["event_id"] as? String else {
            print("PushNotification: Missing event_id")
            completionHandler(.failed)
            return
        }
        
        PushKt.handleNotification(userId: userId, roomId: roomId, eventId: eventId)
        completionHandler(.noData)
    }
}
