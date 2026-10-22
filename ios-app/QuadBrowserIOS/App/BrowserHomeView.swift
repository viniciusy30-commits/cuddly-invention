import SwiftUI
import WebKit

struct BrowserHomeView: View {
    @State private var expandedAccount: Int? = nil
    @State private var reloadTokens = Array(repeating: 0, count: 4)

    private let gameURL = URL(string: "https://pokeidle.io")!

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Image(systemName: "square.grid.2x2.fill")
                    .font(.title2)
                    .foregroundStyle(.cyan)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Cuddly Invention")
                        .font(.headline)
                    Text("Pokéidle • 4 contas isoladas")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if expandedAccount != nil {
                    Button {
                        expandedAccount = nil
                    } label: {
                        Image(systemName: "square.grid.2x2")
                            .font(.title3)
                    }
                    .accessibilityLabel("Mostrar as quatro contas")
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)

            if let account = expandedAccount {
                accountPane(account)
            } else {
                GeometryReader { geometry in
                    let gap: CGFloat = 6
                    let paneHeight = max(220, (geometry.size.height - gap) / 2)
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: gap), GridItem(.flexible(), spacing: gap)], spacing: gap) {
                        ForEach(0..<4, id: \.self) { account in
                            accountCard(account)
                                .frame(height: paneHeight)
                        }
                    }
                    .padding(6)
                }
            }
        }
        .background(Color(red: 0.055, green: 0.07, blue: 0.10))
        .foregroundStyle(.white)
    }

    @ViewBuilder
    private func accountCard(_ account: Int) -> some View {
        VStack(spacing: 0) {
            paneToolbar(account)
            GameWebView(
                account: account,
                url: gameURL,
                reloadToken: reloadTokens[account]
            )
            .clipShape(RoundedRectangle(cornerRadius: 10))
        }
        .background(Color(red: 0.10, green: 0.13, blue: 0.18))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.white.opacity(0.10), lineWidth: 1))
        .contentShape(Rectangle())
    }

    private func accountPane(_ account: Int) -> some View {
        VStack(spacing: 0) {
            paneToolbar(account)
            GameWebView(account: account, url: gameURL, reloadToken: reloadTokens[account])
        }
    }

    private func paneToolbar(_ account: Int) -> some View {
        HStack(spacing: 6) {
            Circle().fill(Color.cyan).frame(width: 7, height: 7)
            Text("Conta \(account + 1)")
                .font(.system(size: 12, weight: .semibold))
            Spacer(minLength: 2)
            Button {
                reloadTokens[account] += 1
            } label: {
                Image(systemName: "arrow.clockwise")
                    .font(.system(size: 12, weight: .semibold))
                    .frame(width: 28, height: 28)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Recarregar conta \(account + 1)")
            Button {
                expandedAccount = expandedAccount == account ? nil : account
            } label: {
                Image(systemName: expandedAccount == account ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right")
                    .font(.system(size: 11, weight: .semibold))
                    .frame(width: 28, height: 28)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel("Alternar tamanho da conta \(account + 1)")
        }
        .padding(.horizontal, 7)
        .frame(height: 38)
        .background(Color(red: 0.12, green: 0.17, blue: 0.23))
    }
}

private struct GameWebView: UIViewRepresentable {
    let account: Int
    let url: URL
    let reloadToken: Int

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = WKWebsiteDataStore(forIdentifier: AccountProfiles.identifier(for: account))
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = true
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.allowsBackForwardNavigationGestures = true
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.isOpaque = false
        webView.backgroundColor = UIColor(red: 0.055, green: 0.07, blue: 0.10, alpha: 1)
        webView.scrollView.backgroundColor = webView.backgroundColor
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        let previous = context.coordinator.lastReloadToken
        if previous != reloadToken {
            context.coordinator.lastReloadToken = reloadToken
            webView.reload()
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(lastReloadToken: reloadToken)
    }

    final class Coordinator {
        var lastReloadToken: Int
        init(lastReloadToken: Int) {
            self.lastReloadToken = lastReloadToken
        }
    }
}
