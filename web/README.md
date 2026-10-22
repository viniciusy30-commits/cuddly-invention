# Quad Pokeidle — versão web gratuita

Esta pasta contém uma página estática instalável na tela inicial do celular (PWA). Não precisa de Mac, Xcode nem assinatura Apple Developer.

## O que faz
- Mostra até quatro painéis do Pokéidle.
- Oferece recarga, expansão de painel e abertura direta do jogo em outra aba.
- Pode ser adicionada à tela inicial no Safari do iPhone.
- Os arquivos estáticos podem ser hospedados gratuitamente pelo GitHub Pages.

## Limitações importantes
- Um site não consegue criar quatro perfis de navegador independentes no Safari. Cookies e armazenamento do Pokéidle podem ser compartilhados entre os painéis.
- O site do jogo pode bloquear iframe por política de segurança; nesse caso, use o botão "Abrir fora". A página não tenta contornar essas proteções.
- Abrir quatro abas também não cria quatro sessões isoladas. Para isso, seria necessário suporte do próprio jogo ou um aplicativo nativo com armazenamento separado por perfil, instalado e assinado para iOS.
- Esta versão é uma interface de acesso, não uma confirmação de que quatro contas funcionam ao mesmo tempo.

## Publicação no GitHub Pages
O workflow deploy-pages.yml publica o conteúdo desta pasta quando a configuração Pages do repositório está definida para GitHub Actions. Se ainda não estiver, entre em Settings → Pages → Build and deployment → Source → GitHub Actions. Depois execute o workflow em Actions → Deploy Quad Pokeidle web app → Run workflow.

## Instalação no iPhone
Abra a URL publicada no Safari, toque em Compartilhar e selecione Adicionar à Tela de Início. A página precisa estar publicada por HTTPS para funcionar como PWA.