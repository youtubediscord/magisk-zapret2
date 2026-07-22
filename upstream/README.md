# Upstream zapret2 payload

Эта папка — единственная точка интеграции с `bol-van/zapret2`.

`fetch-release.sh` на каждой CI-сборке находит последний стабильный GitHub Release,
скачивает официальный `zapret2-v*.tar.gz`, проверяет SHA-256 asset по данным GitHub
API и отдельно сверяет хэши Android-бинарников из upstream `sha256sum.txt`.

Из release-архива берутся только:

- `binaries/android-arm64/nfqws2` → `arm64-v8a`;
- `binaries/android-arm/nfqws2` → `armeabi-v7a`;
- Lua-файлы из `core-lua.txt`.

Остальные Lua-файлы принадлежат этому проекту и автоматически не заменяются.
В частности, это `custom_funcs.lua`, `zapret-multishake.lua`, `zapret-16kb.lua`,
`zapret-obfs.lua`, `zapret-pcap.lua`, `zapret-tests.lua`, `zapret-wgobfs.lua`,
`zapret-custom.lua` и `init_vars.lua`.

Actions-артефакты по ссылкам вида `/actions/runs/.../artifacts/...` намеренно не
используются: они временные и требуют токен для скачивания из другого репозитория.
Официальный release-архив публичный, содержит те же ARM/ARM64-сборки и согласованный
с ними Lua-код. Артефакты `android-x86` и `android-x86_64` для этого Magisk-модуля
не подходят.

Локальная проверка (Linux):

```sh
upstream/fetch-release.sh ./upstream-payload
```

Каталог назначения должен отсутствовать или быть пустым.
