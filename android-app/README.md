# Multi Account

Projeto Android nativo em Kotlin com quatro WebViews simultâneos. A visualização
principal mostra os quatro espaços em uma grade 2×2; o botão de visualização
também permite abrir uma instância por vez em quatro abas horizontais, com
deslize para trocar de instância. Cada espaço usa um perfil nomeado diferente do AndroidX WebKit:
`webview1`, `webview2`, `webview3` e `webview4`.

## Isolamento de sessão

`WebView.setDataDirectorySuffix()` **não é uma API por instância**. Ela é
process-wide, só pode ser chamada uma vez e precisa ser executada antes da
criação de qualquer WebView. Por isso, `QuadBrowserApplication` define um único
sufixo de diretório (`quad-browser`) no início do processo.

O isolamento entre os quatro WebViews é feito pela API `MULTI_PROFILE` do
AndroidX WebKit, que cria um armazenamento de cookies, DOM storage e cache
separado para cada nome de perfil. Registrar quatro Activities com quatro
valores de `android:process` não resolve o problema: cada processo teria sua
própria janela e não seria possível compor quatro Views de processos diferentes
em uma única Activity 2×2.

Em aparelhos com Android System WebView antigo que não oferece `MULTI_PROFILE`,
o app mostra uma mensagem e não inicia uma configuração que compartilharia
sessões silenciosamente.

## Build

Abra a pasta `android-app` no Android Studio. O `app/build.gradle.kts` já
inclui as dependências necessárias:

- AndroidX Core KTX
- AndroidX AppCompat
- AndroidX WebKit `1.12.1`

Também já estão configurados `INTERNET`, orientação retrato e o tema da tela.

## Tela cheia

O app inicia em modo imersivo, escondendo a barra de notificações e a barra de
navegação para aproveitar toda a área disponível. Um gesto de deslizar a partir
da borda pode revelar temporariamente as barras do Android.

Cada quadrante tem um botão `⛶` na barra de controles. Toque nele para ampliar
esse navegador e ocultar os outros três. Toque novamente no botão `×` para
voltar à visualização escolhida. O botão central de visualização alterna entre
a grade 2×2 e o modo de uma instância por aba. A sessão, cookies e navegação
de cada espaço continuam preservados durante a troca de visualização.

## Build gratuito pelo GitHub Actions

O workflow `.github/workflows/android.yml` compila o APK automaticamente a
cada push em `main` ou `master`, em pull requests e manualmente pela aba
**Actions** do GitHub. O APK fica disponível em **Artifacts** com o nome
`quad-browser-debug` por 14 dias.

O workflow instala Java 17, Android SDK API 35, Build Tools 35.0.0 e Gradle
8.9 no runner gratuito do GitHub. Não é necessário manter um computador ligado
nem cadastrar uma chave de assinatura para gerar o APK debug.

## Controle de acesso

O APK valida o endereço de rede no serviço privado de controle antes de liberar os quatro navegadores. A validação é feita no servidor e o aplicativo não contém lista de IPs nem tela para desbloqueio.

Para gerar um APK conectado ao seu painel, informe a URL base da API (incluindo /api) ao compilar:

    ./gradlew assembleDebug -PaccessControlUrl=https://SEU-DOMINIO/api

Se a URL não for configurada ou o serviço estiver indisponível, o app bloqueia a abertura por segurança.
