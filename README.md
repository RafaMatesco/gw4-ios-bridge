# ⌚📲 GW4 iOS Bridge (Galaxy Watch 4 ↔ iPhone)

Uma ponte nativa open-source em Kotlin para conectar o **Samsung Galaxy Watch 4 (Wear OS)** diretamente ao **iPhone (iOS)** — restaurando notificações em tempo real, controle de chamadas telefônicas e sincronização automática de saúde, passos, batimentos cardíacos (BPM), calorias e exercícios com o **Apple Health (App Saúde)** e **Strava**, sem precisar instalar nenhum aplicativo de terceiros no iPhone.

---

## 💡 Sobre o Projeto

Ao migrar de um smartphone Android/Samsung para um iPhone, usuários do **Samsung Galaxy Watch 4** esbarram em um bloqueio artificial de ecossistema: o aplicativo *Galaxy Wearable* para iOS não oferece suporte à linha Watch 4 em diante, tornando o relógio incompatível com o iPhone.

Este projeto nasceu a partir de um estudo de **engenharia reversa** (utilizando o framework [REA](https://github.com/morluto/rea) e depuração ADB Wireless) sobre os protocolos Bluetooth Low Energy (BLE) nativos do iOS. Durante a análise, exploramos como o próprio iOS já disponibiliza nativamente um servidor **ANCS (*Apple Notification Center Service*)** no nível do sistema operacional — o mesmo protocolo utilizado pelo Apple Watch para receber notificações e gerenciar ligações sem depender de apps extras rodando em segundo plano no celular.

Combinando um **Cliente GATT ANCS**, um **Servidor GATT de Saúde padrão Bluetooth SIG (`0x180D` + `0x1814`)** e um **Cofre Offline com sincronização incremental por Delta (`Δ`)** integrado ao app nativo **Atalhos (Shortcuts)** do iOS, este aplicativo devolve vida útil completa ao relógio no ecossistema da Apple.

### Principais Funcionalidades
- 🔔 **Notificações em Tempo Real (Apple ANCS)**: Recebe notificações de qualquer aplicativo do iPhone (WhatsApp, Instagram, LinkedIn, E-mail, Bancos, iMessage, etc.) com vibração no pulso. Dispensar a notificação no relógio envia o comando negativo de volta pelo *ANCS Control Point* e limpa a notificação no iPhone automaticamente.
- 📞 **Controle de Chamadas Recebidas**: Detecta ligações telefônicas, WhatsApp e FaceTime no iPhone e exibe botões no relógio para **Atender** (`ActionIDPositive`) ou **Recusar** (`ActionIDNegative`) diretamente pelo pulso.
- ❤️ **Transmissão Bluetooth de Saúde em Tempo Real (`0x180D` & `0x1814`)**: Transforma o relógio em um sensor Bluetooth oficial de Frequência Cardíaca (`0x180D` com `Energy Expended` a 1 Hz) e Cadência/Distância (`0x1814`), funcionando 100% offline na rua com apps de treino no iPhone (como **Strava**, **Nike Run Club**, **Fitness**, etc.).
- 🏃 **Sincronização Automática com o Apple Health (Delta `Δ` + Cofre Offline Multi-Dias)**: Lê os sensores físicos do relógio (`TYPE_STEP_COUNTER` e `TYPE_HEART_RATE`), calcula calorias ativas (MET + BPM) e basais, armazena o histórico de múltiplos dias na memória flash e expõe uma API local (`/health`) que envia apenas o **Delta incremental** (diferença desde a última leitura) para nunca duplicar passos ou calorias no Apple Health.

---

## 📱⌚ Compatibilidade de Dispositivos

Este projeto foi desenvolvido, calibrado e validado na prática utilizando **dois dispositivos específicos**:
- **Relógio testado**: **Samsung Galaxy Watch 4 (44mm, modelo `SM-R870`)** rodando **Wear OS 4 / One UI Watch (Android 13 / API 33)**.
- **Smartphone testado**: **iPhone 15 Pro** rodando **iOS 26.6.2**.

### Em quais outros dispositivos deve funcionar?
Embora o teste em condições reais tenha sido feito exclusivamente no par **Galaxy Watch 4 (`SM-R870`) + iPhone 15 Pro**, o projeto utiliza apenas APIs padrões do **Android/Wear OS (API 30+)** e especificações universais do **Bluetooth Low Energy (Apple ANCS + Bluetooth SIG GATT)**:

| Categoria | Dispositivos Provavelmente Compatíveis | Observações |
| :--- | :--- | :--- |
| **Smartwatches Samsung** | **Galaxy Watch 4 Classic**, **Galaxy Watch 5 / 5 Pro**, **Galaxy Watch 6 / 6 Classic**, **Galaxy Watch 7 / Ultra** | Todos rodam Wear OS 3.5 / 4 / 5 sobre a mesma arquitetura de sensores e pilha Bluetooth Android utilizada neste projeto. |
| **Outros Smartwatches Wear OS** | **Google Pixel Watch (1, 2, 3)**, **TicWatch Pro 3 / Pro 5**, **Xiaomi Watch 2 Pro**, **Fossil Gen 6** |Qualquer relógio com **Wear OS 3.0+ (Android 11 / API 30 ou superior)** e suporte a BLE Peripheral/Central deve conseguir instalar o APK e utilizar o ANCS e os sensores padrões. *(Nota: Relógios antigos como Galaxy Watch 3 ou Active 2 rodam **Tizen OS** e não suportam APKs Android).* |
| **iPhones (iOS)** | **iPhone X, XR, XS, 11, 12, 13, 14, 15, 16 (e variantes Pro/Max/Plus/SE)** | O protocolo **ANCS (`7905F431-B5CE-4E99-A40F-4B1E122D00D0`)** é nativo de todos os iPhones com Bluetooth 4.0+, e o app **Atalhos (Shortcuts)** com a ação *Registrar Amostra de Saúde* está presente desde o **iOS 13+**. |

> **Contribuições são bem-vindas!** Se você testar em outro modelo de Galaxy Watch, Pixel Watch ou versão de iPhone, abra uma *Issue* ou *Pull Request* contando o resultado para adicionarmos à lista oficial de dispositivos verificados.

---

## 🛠️ Como Aplicar Este Projeto no Seu Relógio e iPhone (Passo a Passo)

Qualquer pessoa pode reproduzir este setup em casa em poucos minutos. Você não precisa de cabo especial para o relógio (tudo é feito via Wi-Fi).

### Passo 1: Preparar o Relógio (Ativar Depuração por Wi-Fi)
1. Conecte o seu relógio na mesma rede Wi-Fi do seu computador (e do seu iPhone).
2. No relógio, vá em **Configurações -> Sobre o relógio -> Informações do software**.
3. Toque **7 vezes seguidas** em cima de **Versão de software** até aparecer a mensagem *"Modo de desenvolvedor ativado"*.
4. Volte uma tela e entre em **Opções do desenvolvedor** (logo abaixo de *Sobre o relógio*).
5. Ative **Depuração ADB** e depois entre em **Depuração por Wi-Fi** e ative a chave.
6. Toque em **"Parear novo dispositivo"**: a tela mostrará um **Código de pareamento de 6 dígitos** e um **Endereço IP:Porta** (ex: `192.168.15.72:33745`).

### Passo 2: Instalar o Aplicativo no Relógio via ADB
Você pode usar o APK já compilado disponível neste repositório em [`app/build/outputs/apk/debug/app-debug.apk`](app/build/outputs/apk/debug/app-debug.apk) ou compilar do zero com `./gradlew assembleDebug`.

No terminal do seu computador (com o [Android Platform Tools / ADB](https://developer.android.com/tools/releases/platform-tools) instalado):

```bash
# 1. Faça o pareamento inicial usando o IP:Porta de pareamento e o código de 6 dígitos
adb pair <IP_DO_RELOGIO>:<PORTA_DE_PAREAMENTO> <CODIGO_6_DIGITOS>

# 2. Conecte na porta principal exibida na tela "Depuração por Wi-Fi" do relógio
adb connect <IP_DO_RELOGIO>:<PORTA_PRINCIPAL>

# 3. Instale o APK no relógio
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 4. Inicie o aplicativo no relógio
adb shell am start -n com.rafa.gw4bridge/.MainActivity
```

Ao abrir o **GW4 iOS Bridge** no relógio pela primeira vez, aceite todas as permissões na tela (Bluetooth, Sensores Corporais, Atividade Física, Localização e Notificações).

---

### Passo 3: Parear o Bluetooth com o iPhone (Notificações, Chamadas e Strava BLE)
1. Com o app **GW4 iOS Bridge** aberto no relógio (ele já estará anunciando via Bluetooth Low Energy), abra no seu iPhone: **Ajustes -> Bluetooth**.
2. Caso o seu relógio ainda não esteja pareado no Bluetooth do iPhone (ou faça o pareamento nas configurações de Bluetooth do relógio procurando pelo iPhone), toque no nome do relógio/iPhone e confirme o código PIN nas duas telas.
3. No iPhone, em **Ajustes -> Bluetooth**, toque no ícone **`(i)`** azul ao lado do nome do seu **Galaxy Watch 4** e ative a chave:
   - ✅ **Compartilhar Notificações do Sistema** (*Share System Notifications*)
4. No relógio, toque no botão **"🔄 Reconectar iPhone (ANCS)"** (ou aguarde 5 segundos). O status no topo do relógio mudará para verde:
   - `Ativo: ANCS + Chamadas (iPhone de ...)`
5. **Para usar o batimento cardíaco ao vivo na rua com o Strava / apps de treino (100% via Bluetooth, sem Wi-Fi)**:
   - Abra o app do **Strava** no iPhone -> iniciar gravação de atividade -> ícone de **Sensor Cardíaco** -> selecione o **Galaxy Watch4**. O batimento cardíaco será transmitido a cada 1 segundo via perfil Bluetooth `0x180D` durante todo o exercício!

---

### Passo 4: Configurar a Sincronização Automática com o Apple Health (App Saúde)
Para gravar automaticamente seus **Passos do dia a dia**, **Calorias Ativas**, **Calorias Basais** e **Batimentos Cardíacos** no **Apple Health**, usamos o app nativo **Atalhos (Shortcuts)** do iPhone consultando o endpoint incremental do relógio (`http://<IP_DO_RELOGIO>:8765/health`):

#### 4.1. Criar o Atalho no iPhone
1. Abra o app **Atalhos (Shortcuts)** no iPhone e toque em **`+`** para criar um novo atalho chamado **"Sincronizar Watch 4"**.
2. Adicione a ação **"Obter Conteúdo de URL"** (*Get Contents of URL*):
   - URL: `http://<IP_DO_SEU_RELOGIO>:8765/health` *(o IP aparece na última linha da tela do app no relógio)*.
3. **Para registrar Passos (Delta incremental)**:
   - Adicione **"Obter Valor do Dicionário"** -> Chave: `steps` (em *Conteúdo do URL*).
   - Adicione **"Registrar Amostra de Saúde"** (*Log Health Sample*) -> Tipo: **Passos** -> Valor: *Valor do Dicionário* -> Data: *Data Atual*.
4. **Para registrar Calorias Ativas (Delta incremental)**:
   - Adicione **"Obter Valor do Dicionário"** -> Chave: `activeEnergyKcal` (em *Conteúdo do URL*).
   - Adicione **"Registrar Amostra de Saúde"** -> Tipo: **Energia Ativa** -> Unidade: `kcal` -> Valor: *Valor do Dicionário*.
5. **Para registrar Batimentos Cardíacos (BPM)**:
   - Adicione **"Obter Valor do Dicionário"** -> Chave: `heartRateBpm` (em *Conteúdo do URL*).
   - Adicione **"Registrar Amostra de Saúde"** -> Tipo: **Frequência Cardíaca** -> Unidade: `count/min` -> Valor: *Valor do Dicionário*.

> 💡 **Sem duplicação de dados**: O relógio calcula e envia nos campos `steps`, `activeEnergyKcal`, `basalEnergyKcal` e `distanceKm` **apenas a diferença nova (Delta `Δ`)** desde a última execução do atalho! Se o atalho rodar 10 vezes seguidas enquanto você estiver parado, ele retornará `0` nas 9 seguintes e nunca duplicará suas calorias ou passos no Apple Health.

#### 4.2. Deixar 100% Automático (Zero Toques)
Na aba **Automação** do app **Atalhos** no iPhone, toque em **`+`** e crie dois gatilhos marcados com **"Executar Imediatamente"** (*Run Immediately*) apontando para o atalho **"Sincronizar Watch 4"**:
1. **Quando conectar ao Wi-Fi** (escolha a rede Wi-Fi da sua casa): assim que você chegar da rua, todo o histórico acumulado no **Cofre Offline** do relógio é despejado automaticamente no Apple Health.
2. **Ao abrir o app Saúde (ou WhatsApp/Fitness)**: mantém os gráficos do Apple Health atualizados ao longo do dia sem você precisar tocar em nenhum botão.

---

## 📂 Arquitetura do Código-Fonte

```text
gw4-ios-bridge/
└── app/src/main/
    ├── AndroidManifest.xml
    └── java/com/rafa/gw4bridge/
        ├── AncsProtocol.kt               # Parser e construtor binário do protocolo Apple ANCS (Notification/Data Source + Control Point)
        ├── BleAncsManager.kt             # Cliente BLE GATT (ANCS) + Servidor BLE GATT (0x180D Heart Rate, 0x180A Device Info, 0x1814 Cadence)
        ├── HealthSyncManager.kt          # Sensores de passos/BPM, cálculo de calorias, Cofre Offline Multi-Dias, Delta Sync e Servidor HTTP :8765
        ├── BridgeService.kt              # Foreground Service persistente (24/7) que orquestra notificações, chamadas e saúde
        ├── NotificationActionReceiver.kt # Captura ações de Atender/Recusar/Dispensar no relógio e envia ao iPhone via ANCS
        ├── BootReceiver.kt               # Inicialização automática junto com o boot do Wear OS
        └── MainActivity.kt               # Interface circular nativa para Wear OS (Dashboard ao vivo + controle de treinos)
```

---

## 📄 Licença
Distribuído sob a licença MIT. Sinta-se livre para fazer fork, adaptar para outros relógios Wear OS e contribuir!
