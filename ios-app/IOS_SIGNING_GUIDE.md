# Assinatura do app iOS (sem Mac próprio)

O workflow `.github/workflows/ios-device-ipa.yml` compila o app para iPhone e exporta um IPA assinado. Ele não consegue assinar sozinho: a Apple exige um certificado e um perfil de provisionamento válidos.

## Secrets necessários no GitHub

No repositório, acesse **Settings → Secrets and variables → Actions → New repository secret** e crie estes quatro secrets:

- `IOS_CERTIFICATE_BASE64`: conteúdo Base64 de um certificado Apple Distribution exportado como arquivo `.p12`.
- `IOS_CERTIFICATE_PASSWORD`: senha usada para proteger o arquivo `.p12`.
- `IOS_PROVISIONING_PROFILE_BASE64`: conteúdo Base64 de um perfil de provisionamento para o Bundle ID `com.viniciusy30.quadbrowser.ios`, compatível com distribuição App Store Connect.
- `IOS_TEAM_ID`: Team ID da conta Apple Developer.

**Não envie certificado, senha, chave privada ou arquivos de assinatura neste chat nem os coloque no repositório.** Use somente GitHub Secrets. Não use credenciais de outra pessoa sem autorização.

## Requisito da Apple

Para distribuir via TestFlight/App Store, normalmente é necessário participar do Apple Developer Program pago. Um IPA com assinatura App Store Connect não é instalado tocando no arquivo: ele precisa ser enviado ao App Store Connect e distribuído por TestFlight, ou por outro método compatível com o tipo de assinatura/perfil. Uma conta Apple gratuita não substitui esse processo de distribuição.

## Executar

Depois de cadastrar os secrets, abra **Actions → Build signed iOS IPA → Run workflow** e selecione a branch `feature/ios-pokeidle-four-accounts`. Se terminar com sucesso, baixe o artefato `CuddlyInvention-iOS-IPA` na página da execução.

Este workflow ainda precisa ser executado com credenciais válidas e testado num iPhone físico. A compilação bem-sucedida não garante que pokeidle.io aceite quatro sessões independentes ao mesmo tempo.
