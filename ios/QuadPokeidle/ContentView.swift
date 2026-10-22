import SwiftUI
import WebKit

struct ContentView: View {
    @State private var reloadTokens = Array(repeating: UUID(), count: 4)

    private let gameURL = URL(string: "https://pokeidle.io/app")!

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("QUAD POKEIDLE")
                        .font(.headline.bold())
                    Text("4 painéis independentes")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button {
                    for i in reloadTokens.indices { reloadTokens[i] = UUID() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.headline)
                        .padding(10)
                        .background(.white.opacity(0.10), in: Circle())
                }
                .accessibilityLabel("Recarregar as quatro contas")
            }
            .padding(12)

            Text("Faça login em cada painel. Se o jogo não carregar ou bloquear WebViews, o painel mostrará o erro; isso precisa ser validado no iPhone.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 12)
                .padding(.bottom, 8)

            GeometryReader { geometry in
                let gap: CGFloat = 6
                let cardWidth = (geometry.size.width - gap) / 2
                let cardHeight = (geometry.size.height - gap) / 2

                LazyVGrid(
                    columns: [GridItem(.fixed(cardWidth)), GridItem(.fixed(cardWidth))],
                    spacing: gap
                ) {
                    ForEach(0..<4, id: \.self) { index in
                        VStack(spacing: 0) {
                            HStack(spacing: 4) {
                                Text("CONTA \(index + 1)")
                                    .font(.caption.bold())
                                    .lineLimit(1)
                                Spacer(minLength: 2)
                                Button {
                                    reloadTokens[index] = UUID()
                                } label: {
                                    Image(systemName: "arrow.clockwise")
                                        .font(.caption.bold())
                                        .frame(minWidth: 28, minHeight: 28)
                                }
                                .accessibilityLabel("Recarregar conta \(index + 1)")
                            }
                            .padding(.horizontal, 6)
                            .background(Color(red: 0.10, green: 0.15, blue: 0.24))

                            GameWebView(url: gameURL, reloadToken: reloadTokens[index])
                                .background(.white)
                        }
                        .frame(width: cardWidth, height: cardHeight)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(.white.opacity(0.14)))
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .padding(.horizontal, 6)
            .padding(.bottom, 6)
        }
        .background(Color(red: 0.035, green: 0.05, blue: 0.09))
        .foregroundStyle(.white)
    }
}

private struct GameWebView: UIViewRepresentable {
    let url: URL
    let reloadToken: UUID

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        // A unique ephemeral store per panel isolates cookies and local website data.
        configuration.websiteDataStore = .nonPersistent()

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = true
        webView.scrollView.isScrollEnabled = true
        webView.isOpaque = false
        webView.backgroundColor = .white
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        if context.coordinator.lastReloadToken != reloadToken {
            context.coordinator.lastReloadToken = reloadToken
            webView.load(URLRequest(url: url))
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(lastReloadToken: reloadToken)
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        var lastReloadToken: UUID

        init(lastReloadToken: UUID) {
            self.lastReloadToken = lastReloadToken
        }

        // Some games open login links or popups with window.open(). Load those
        // requests in the same panel rather than launching Safari or a new tab.
        func webView(
            _ webView: WKWebView,
            createWebViewWith configuration: WKWebViewConfiguration,
            for navigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            guard navigationAction.targetFrame == nil,
                  let targetURL = navigationAction.request.url else { return nil }
            webView.load(URLRequest(url: targetURL))
            return nil
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            showLoadError(in: webView, error: error)
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            showLoadError(in: webView, error: error)
        }

        private func showLoadError(in webView: WKWebView, error: Error) {
            let nsError = error as NSError
            guard nsError.code != NSURLErrorCancelled else { return }
            let message = "Não foi possível carregar este painel.\n\n\(error.localizedDescription)\n\nToque em ↻ para tentar novamente."
            let escaped = message
                .replacingOccurrences(of: "&", with: "&amp;")
                .replacingOccurrences(of: "<", with: "&lt;")
                .replacingOccurrences(of: ">", with: "&gt;")
                .replacingOccurrences(of: "\n", with: "<br>")
            webView.loadHTMLString(
                "<html><meta name='viewport' content='width=device-width, initial-scale=1'><body style='font-family:-apple-system;padding:16px;color:#222;font-size:14px'>\(escaped)</body></html>",
                baseURL: nil
            )
        }
    }
}
