import SwiftUI
import WebKit

struct ContentView: View {
    @State private var reloadTokens = Array(repeating: UUID(), count: 4)
    @State private var activeAccount = 0

    private let gameURL = URL(string: "https://pokeidle.io/app")!

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("QUAD POKEIDLE")
                        .font(.headline.bold())
                    Text("4 sessões • teste nativo")
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
            .padding(14)

            Text("Entre em uma conta diferente em cada painel. Os painéis usam armazenamento de sessão separado; teste se o jogo permite login e funcionamento em quatro WebViews.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 14)
                .padding(.bottom, 10)

            GeometryReader { geometry in
                let gap: CGFloat = 8
                let cardWidth = (geometry.size.width - gap) / 2
                let cardHeight = (geometry.size.height - gap) / 2

                LazyVGrid(
                    columns: [GridItem(.fixed(cardWidth)), GridItem(.fixed(cardWidth))],
                    spacing: gap
                ) {
                    ForEach(0..<4, id: \.self) { index in
                        VStack(spacing: 0) {
                            HStack {
                                Text("CONTA \(index + 1)")
                                    .font(.caption.bold())
                                Spacer()
                                Button {
                                    reloadTokens[index] = UUID()
                                } label: {
                                    Image(systemName: "arrow.clockwise")
                                        .font(.caption.bold())
                                }
                                .accessibilityLabel("Recarregar conta \(index + 1)")
                            }
                            .padding(.horizontal, 8)
                            .padding(.vertical, 7)
                            .background(Color(red: 0.10, green: 0.15, blue: 0.24))

                            GameWebView(url: gameURL, reloadToken: reloadTokens[index])
                                .background(.white)
                        }
                        .frame(width: cardWidth, height: cardHeight)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(.white.opacity(0.14)))
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .padding(.horizontal, 8)
            .padding(.bottom, 8)
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
        // Separate ephemeral stores keep each account isolated from the other panels.
        // Sessions survive navigation while this app instance remains alive, but are not
        // promised to persist after the app is force-quit or the device restarts.
        configuration.websiteDataStore = .nonPersistent()

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.allowsBackForwardNavigationGestures = true
        webView.scrollView.isScrollEnabled = true
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        // Reload only when the token changes; do not reset the page on ordinary redraws.
        if context.coordinator.lastReloadToken != reloadToken {
            context.coordinator.lastReloadToken = reloadToken
            webView.load(URLRequest(url: url))
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(lastReloadToken: reloadToken)
    }

    final class Coordinator {
        var lastReloadToken: UUID
        init(lastReloadToken: UUID) {
            self.lastReloadToken = lastReloadToken
        }
    }
}
