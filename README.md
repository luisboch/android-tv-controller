# ATV Remote

Controle remoto pela rede para a TV box Android (H_BOX_TS, Android 10).

- `tv/`: app que roda na box. Sobe no boot um serviço em segundo plano que aceita comandos apenas de celulares pareados.
- `phone/`: app do celular. Tem só o botão **Parear** até parear, depois mostra o controle.
- `common/`: protocolo e criptografia, compartilhados pelos dois apps.

## Build

Toolchain via [mise](https://mise.jdx.dev) (`mise.toml`: Java 21, Gradle, Android SDK).

```bash
mise exec -- ./gradlew :tv:assembleRelease :phone:assembleRelease :common:test
```

APKs gerados:
- `tv/build/outputs/apk/release/tv-release.apk`
- `phone/build/outputs/apk/release/phone-release.apk`

## Instalar na box

```bash
adb connect 10.0.3.118:5555
adb -s 10.0.3.118:5555 install -r tv/build/outputs/apk/release/tv-release.apk
adb -s 10.0.3.118:5555 shell am start -n dev.atvremote.tv/.MainActivity
```

Abrir o app uma vez depois de instalar tira ele do estado "parado". A partir daí ele sobe sozinho a cada boot.
A tela do app na TV ("ATV Remote") lista os celulares pareados e tem a opção de remover os pareamentos.

### Por que o app da TV consegue apertar teclas

Um app comum não injeta teclas em outros apps. O firmware desta box é assinado com a chave de plataforma
**pública de teste do AOSP** (`keys/`, SHA-256 `C8:A2:E9:BC:…:2A:B8`). Assinado com ela, o app roda como
`android.uid.system` e tem `INJECT_EVENTS`. Isso só funciona em firmwares assinados com essa chave de teste.

## Protocolo

| Porta | Uso |
|---|---|
| UDP 47100 | Descoberta: o celular faz broadcast de `ATVC?` e a TV responde `ATVC!<nome>` |
| TCP 47101 | Pareamento |
| UDP 47102 | Comandos |

**Pareamento**
1. O celular gera um UUID e um par de chaves ECDH P-256 e envia a chave pública para a TV.
2. A TV responde com a sua chave pública e mostra um PIN de 4 dígitos (popup + notificação).
3. Os dois lados calculam `chave = HKDF-SHA256(ECDH, salt = PIN, info = UUID)` (128 bits).
4. O celular prova que tem o PIN com um HMAC. A TV confirma com outro HMAC e salva UUID + chave.
5. São até 3 tentativas de PIN por sessão.

**Comando** (55 bytes por pacote): `UUID || nonce || AES-128-GCM(contador, keycode, ação) || tag`.
- O UUID entra como AAD.
- A TV descarta pacotes de UUID desconhecido, que não passam na autenticação ou com contador que não seja maior que o último recebido (proteção contra replay).

## Pareamento antigo no boot (desligado)

O fluxo do Automate (`/sdcard/Download/start-app-boot.flo`) abria `com.hcy.myapplication/.PairActivity` no boot.
O Automate foi desativado. Para reverter:

```bash
adb -s 10.0.3.118:5555 shell pm enable com.llamalab.automate
```
