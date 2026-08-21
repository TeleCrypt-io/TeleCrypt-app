import UIKit
import SwiftUI
import TeleCryptUI

struct MainView: UIViewControllerRepresentable {
    let lifecycle: LifecycleRegistry
    
    func makeUIViewController(context: Context) -> UIViewController {
        return MainViewControllerKt.MainViewController(lifecycle: lifecycle)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
