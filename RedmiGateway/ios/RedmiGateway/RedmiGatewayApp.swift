import SwiftUI

@main
struct RedmiGatewayApp: App {
    init() {
        // Initialize CallKitManager to bind delegate eagerly
        _ = CallKitManager.shared
    }
    
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
