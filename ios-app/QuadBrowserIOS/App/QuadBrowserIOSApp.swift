import SwiftUI

@main
struct QuadBrowserIOSApp: App {
    var body: some Scene {
        WindowGroup {
            BrowserHomeView()
                .preferredColorScheme(.dark)
        }
    }
}

enum AccountProfiles {
    static func identifier(for account: Int) -> UUID {
        let key = "pokeidle.profile.\(account).uuid"
        if let stored = UserDefaults.standard.string(forKey: key), let uuid = UUID(uuidString: stored) {
            return uuid
        }
        let uuid = UUID()
        UserDefaults.standard.set(uuid.uuidString, forKey: key)
        return uuid
    }
}
