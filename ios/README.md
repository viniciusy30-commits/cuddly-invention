# Quad Pokeidle para iPhone — protótipo nativo

Esta pasta contém o início de um app iOS nativo com quatro WKWebViews independentes, em vez de quatro iframes HTML. Isso pode evitar a restrição de incorporação que o site aplica a iframes, pois cada painel navega diretamente para `https://pokeidle.io/app`.

## Estado real

- É um protótipo de código, ainda não testado no iPhone.
- Cada painel recebe um `WKWebsiteDataStore.nonPersistent()` separado. Isso isola cookies/dados entre as quatro sessões enquanto o app está em execução.
- Como o armazenamento é temporário, não se promete manter os logins depois de encerrar à força o app.
- O PokeIdle pode detectar ou bloquear WebViews, e o iOS pode reduzir atividade em segundo plano. Quatro painéis visíveis não garantem quatro sessões sempre ativas.
- Não há IPA pronto nem assinatura nesta pasta.

## Como instalar sem pagar

A Apple permite testar apps num aparelho pessoal com conta Apple gratuita usando Xcode, mas os perfis de assinatura expiram após 7 dias; é necessário reinstalar/assinar novamente. Para esse método, é necessário acesso a um Mac com Xcode e um cabo para conectar o iPhone. Referência: https://developer.apple.com/help/account/basics/about-your-developer-account/

Este repositório, sozinho, não consegue instalar um app nativo no iPhone nem assinar o IPA com a sua conta Apple. Não insira a senha da Apple em sites desconhecidos que prometem assinatura grátis.

## Próxima etapa de compilação

Crie um projeto iOS App no Xcode (SwiftUI) chamado `QuadPokeidle`, substitua o arquivo principal pelo `QuadPokeidleApp.swift` e adicione `ContentView.swift` ao target. Se o primeiro teste carregar o jogo, o próximo passo é criar armazenamento persistente separado para cada conta e validar o comportamento real.
