# Quad Browser Desktop

Versão desktop para Windows 10/11, adicionada como projeto separado para preservar o aplicativo Android existente.

## Recursos implementados

- Quatro navegadores Chromium na grade 2×2.
- Sessão persistente e separada por navegador (cookies, armazenamento local e login).
- Barra de endereço independente, pesquisa quando o texto não é um endereço, voltar, avançar e recarregar.
- Modo de tela única para ampliar um quadrante e voltar à grade.
- Links que abrem novas janelas são encaminhados ao navegador padrão do sistema.
- Interface em português, adaptada a janelas redimensionáveis.
- Instalador Windows gerado pelo Electron Builder.

O desktop **não inclui autoclicker nem bolha flutuante**.

## Executar localmente

Requer Node.js LTS e npm. No terminal:

```bash
cd desktop-app
npm install
npm start
```

## Gerar instalador

```bash
npm run dist
```

O instalador estará na pasta `desktop-app/release`.

## Segurança e limites

O renderer não recebe acesso direto ao Node.js; as operações de navegação passam por uma API pequena exposta pelo preload. Os endereços `javascript:`, `file:` e `data:` são bloqueados na barra. As sessões são separadas por partições persistentes. Como qualquer navegador Chromium, alguns sites podem impedir login embutido, reprodução protegida ou abrir páginas em pop-ups.

## Compilação pelo GitHub Actions

O workflow `.github/workflows/desktop-windows.yml` instala as dependências, empacota o aplicativo e publica o instalador como artefato para baixar na execução do workflow.
