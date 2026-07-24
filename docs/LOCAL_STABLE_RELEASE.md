# Локальный стабильный релиз

Стабильный production-канал не зависит от завершения GitHub Actions. Actions после
push в `main` в фоне повторяет тесты и сборку и сохраняет run artifacts, но не имеет
права создавать теги или GitHub Releases.

## Порядок выпуска

1. Обновить `version.properties`, проверить изменения, закоммитить и отправить
   `main` в `origin`.
2. Запустить локальную квалификацию точного отправленного коммита:

   ```bash
   .codex/skills/build-zapret2-locally/scripts/build-local-release.sh \
     --channel stable \
     --repo /home/codex-pve/magisk-zapret2
   ```

   Сборка завершается только после source/release-policy проверок, полного shell
   test suite, Android unit tests, release lint, проверки package contract, SHA-256
   и production-сертификата APK. Результат находится в
   `.artifacts/local-releases/v<VERSION>/`.

3. После явного решения опубликовать stable запустить:

   ```bash
   .codex/skills/build-zapret2-locally/scripts/publish-stable-release.sh \
     --repo /home/codex-pve/magisk-zapret2
   ```

Publisher повторно проверяет точный `origin/main`, монотонность `versionCode`,
отсутствие существующего тега/релиза, контрольные суммы, module metadata,
`update.json` и production-подпись APK. Затем он создаёт стабильный тег
`v<VERSION>`, публикует пять assets и помечает релиз как `Latest`.

Существующий тег или релиз никогда не перезаписывается. Если production signer
недоступен или любая проверка не проходит, стабильный релиз не публикуется.

## Локальная dev-сборка

Для проверки текущего рабочего дерева без commit/push:

```bash
.codex/skills/build-zapret2-locally/scripts/build-local-release.sh \
  --channel dev \
  --repo /home/codex-pve/magisk-zapret2
```

Dev-сборка включает tracked, изменённые и новые non-ignored файлы в приватный
snapshot, не меняя checkout. Она проходит те же локальные тесты и получает
production-подпись, чтобы APK можно было установить поверх stable. Артефакты имеют
версию `v<VERSION>-dev.<timestamp>.<sha>` и сохраняются в `.artifacts/dev-builds/`.

Dev-контур не создаёт `update.json`, Git tag, GitHub Release или `Latest` и не
публикуется через stable publisher.
