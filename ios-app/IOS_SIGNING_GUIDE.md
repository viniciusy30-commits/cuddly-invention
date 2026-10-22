# Assinatura do app iOS (sem Mac próprio)

O workflow `.github/workflows/ios-device-ipa.yml` usa um runner macOS do GitHub Actions para compilar o app para iPhone e tentar assinar/exportar um IPA com assinatura automática da Apple. Não é preciso ter um Mac próprio, mas é necessária uma conta Apple Developer com permissões apropriadas.

## 1. Requisito da Apple

Para distribuir pelo TestFlight/App Store, normalmente é necessário participar do Apple Developer Program pago. Uma conta Apple comum não substitui essa associação. O app usa o Bundle ID `com.viniciusy30.quadbrowser.ios`.

## 2. Criar uma chave de API

No App Store Connect, abra **Users and Access → Integrations → App Store Connect API** e crie uma chave com uma função/permissões que permitam administrar os recursos de assinatura necessários. Guarde o arquivo privado `.p8`, o **Key ID** e o **Issuer ID**. A Apple só permite baixar o arquivo privado uma vez; mantenha-o protegido.

Se a sua conta não permitir criar uma chave ou gerar certificados/perfis, o titular ou administrador da equipe Apple Developer precisará configurar isso. Não use credenciais de outra pessoa sem autorização.

## 3. Adicionar GitHub Secrets

No repositório, acesse **Settings → Secrets and variables → Actions → New repository secret** e crie:

- `IOS_APP_STORE_CONNECT_KEY_P8`: conteúdo completo do arquivo `.p8`, incluindo as linhas `-----BEGIN PRIVATE KEY-----` e `-----END PRIVATE KEY-----`.
- `IOS_APP_STORE_CONNECT_KEY_ID`: o Key ID da chave.
- `IOS_APP_STORE_CONNECT_ISSUER_ID`: o Issuer ID exibido pela Apple.
- `IOS_TEAM_ID`: Team ID da sua equipe Apple Developer.

**Não envie o arquivo .p8, chaves privadas ou credenciais neste chat, e não os coloque no repositório.** Insira-os apenas nos GitHub Secrets. No Android, é possível usar um site/app confiável para converter o arquivo em Base64 localmente, mas evite enviar uma chave privada para sites de terceiros; prefira uma ferramenta local confiável.

## 4. Executar a compilação

Abra **Actions → Build signed iOS IPA → Run workflow** e selecione a branch `feature/ios-pokeidle-four-accounts`. Se o workflow terminar com sucesso, baixe o artefato `CuddlyInvention-iOS-IPA` na página da execução.

O IPA exportado para App Store Connect não é instalado simplesmente abrindo o arquivo. Para uso normal no iPhone, o caminho esperado é enviar/distribuir pelo App Store Connect e instalar via TestFlight. A execução ainda precisa ser feita com credenciais válidas e testada num iPhone físico; a compilação não garante que pokeidle.io aceite quatro sessões independentes simultâneas.
