# Changelog

## 1.9.1 — source candidate

Accessibility source hardening now gives every custom interactive Compose surface an
explicit 48 dp minimum target, preserves icon-only control names through loading-state
visual changes, marks shared section titles, the active screen title, navigation groups,
and the medium overflow title as semantic headings, and statically prevents disabling
Material touch-target enforcement. The
36 fixed light/dark semantic color pairs are also locked above 4.5:1 contrast.
The full English/Russian catalog now has a source policy for translatable-key parity,
format-argument types and positions, plus complete Russian plural quantities.
Russian destination previews now render the real localized loading/error/disabled state
resources instead of English-only demo copy, and reachable Compose UI rejects literal copy.
Strategy category subtitles now localize the app-owned no-filter state while leaving protocol
and configured file identifiers as exact runtime data.
The localization source boundary now covers ViewModel-produced `UiText` as well as Compose:
stable app-owned copy cannot be introduced through a literal `UiText.Dynamic`, while exact
strategy names, filenames, commands, and redacted diagnostics remain runtime data.
The Config and Strategies runtime-mutation adapters also rethrow coroutine cancellation before
mapping ordinary exceptions to typed failures, with a lifecycle source contract guarding both.
Update-check failures are now typed through the data boundary and localized in Control for
offline, timeout, TLS, HTTP, oversized, empty, invalid-metadata, and generic request cases;
Russian feedback no longer appends an English exception message. Missing release notes likewise
use an English/Russian dialog resource instead of a hardcoded English data-layer sentinel.
Download, preflight, and install outcomes now carry typed failures through the update report
instead of assembling English `Module:`, `APK:`, and deferred diagnostics in the data layer.
Control localizes app-owned failure reasons and composes them into partial-update feedback while
retaining only bounded, redacted recovery details as dynamic technical diagnostics. APK and
module preflight failures now use 18 typed English/Russian validation messages instead of
showing validator-authored English prose in localized UI.
Fatal update-recovery details now retain a localized heading and survive process recreation
only as an allowlisted resource plus a separately bounded and redacted diagnostic argument.
Config, Hosts, DNS and Presets action groups now stack for narrow windows and large text
instead of squeezing long localized labels into fixed horizontal button rows. Control
readiness badges follow the same policy, and trailing setting values are width-bounded.
The Hostlist editor status/save footer also stacks instead of compressing on phone widths.
Preset editor confirm actions are vertical so long localized labels remain usable in dialogs.
Logs uses a scrollable Material 3 primary tab row so large translated tab labels are not squeezed.
Strategy selection now rejects IDs that are not present in the exact authoritative picker catalog
loaded for that category, before any privileged category write or rollback path can begin.
Config Editor no longer rewrites a clean command on ordinary Save, and forced command-mode
activation is accepted only as part of the explicit restart flow.
Android's independent firewall verifier now binds each live ordinal NFQUEUE payload to every
owner-v6 rule field instead of accepting only matching chains, cardinality and queue number.
Hot-update candidate promotion is now owner/digest-CAS bound instead of using a raw directory
move. Recovery rejects unsafe directory identities before classification, binds owner-rewritten
journal bytes to an exact precomputed digest, and revalidates the root-owned non-link lifecycle
tree before every privileged script execution and terminal journal deletion.
Restored Compose confirmations now close as soon as their baseline or operation authority is
lost, strategy detail state cannot reopen from a stale category key, and parentless restored
editor/detail exits clear the orphan destination before opening Control or Hostlists.
Update and recovery lifecycle preflight now verifies the sourced `common.sh` and
`command-builder.sh` as root-owned single-link `0755` files before executing scripts.
Rollback requires a verified stopped state before moving the active tree. Incomplete
rollback diagnostics now probe the exact safe backup and journal digest, and no longer
claim that the live owner lock remains held when it is deliberately released for recovery.
Hot-update preparation now requires a fresh owner-bound update/backup/failed/recovery
workspace instead of deleting unknown pre-existing trees. Active backup and candidate
promotion repeat full tree and disable-marker integrity checks immediately around each move.
Adaptive navigation now uses a compact drawer below 600 dp, six frequent destinations
plus a localized accessible overflow item at medium widths, and the grouped expanded rail
from 840 dp. Window-mode changes close invisible drawers and discard stale exit prompts.
Reduced motion also fails safe when animator-scale reads fail or return non-finite values.
Release metadata, module-package verification, hostlist imports, downloads, extraction,
and digest passes now share one guaranteed-progress stream primitive, so a zero-progress
provider/network read cannot spin forever and bounded input still fails closed on oversize.
The download producer now also honors its `0..100` callback contract when a server reports
an incorrect `Content-Length`, instead of relying only on the existing UI-side normalization.
Artifact downloads now recognize the complete reviewed GET redirect set, including `303`,
while still revalidating every resolved destination against the HTTPS host/path allowlist.

Эта запись описывает текущее дерево исходников. Она не является заявлением о готовом
подписанном релизе: compile/lint/test, signed-artifact и rooted-device gates пока
отложены.

### Интерфейс

- Полный переход APK на adaptive Jetpack Compose Material 3 Expressive.
- Десять реальных маршрутов, компактная drawer-навигация и wide navigation rail.
- Семантические light/dark/dynamic цвета, expressive typography/shapes/motion и
  поддержка reduced motion.
- Состояния loading, empty, degraded, unsupported, error и destructive confirmation
  для всех основных экранов.
- Сброс DNS теперь требует явного подтверждения, а двойное нажатие «Назад» использует
  монотонное время и сбрасывается при навигации или открытии меню.
- Английская и русская локализации, IME/insets, RTL/large-font preview fixtures и
  доступные роли/описания интерактивных компонентов.

### Логика APK и модуля

- Owner-state v6 синхронизирован между APK и shell как точная схема из 33 полей с
  firewall tag/chain binding.
- Update lock v2, transaction v3 и cleanup v2 используют owner/digest CAS,
  cancellation-safe handoff и восстанавливаемый terminal commit.
- Package/update pipeline проверяет URL, SHA-256, package ID, signer lineage, версию,
  ABI, manifest allowlist, режимы и точный installed publication state.
- Runtime, categories, strategies, presets, command line, hosts и hostlists используют
  typed fail-closed чтение, bounded ввод, optimistic source comparison, atomic replace,
  post-write verification и rollback/rollback-failed outcomes.
- Каталог, пагинация, поиск и редактор hostlist теперь считают только реальные записи,
  одинаково исключают пустые строки и комментарии и запрещают root-запись
  некорректного или comment-only содержимого; штатные wildcard-маски сохранены.
- Hostlist editor сохраняет точное повторно прочитанное содержимое как optimistic baseline, поэтому
  следующий save не получает ложный `SOURCE_CHANGED` из-за завершающего перевода строки; editor-load,
  search и paging привязаны к generation и неизменяемому query.
- Закрыта последняя UI-only защита от потери черновиков: Config/Hosts reload и Preset/Hostlist
  editor exit не отбрасывают несохранённые изменения без явного discard-acknowledgement внутри
  ViewModel, сброс hosts overlay требует того же подтверждения, повторное открытие preset editor
  не заменяет уже открытый черновик, а сохранение неизменённого hosts не запускает mutation.
- DNS reset, очистка логов и сброс hosts overlay теперь дополнительно отклоняются самой ViewModel,
  если вызов не пришёл из подтверждённой destructive-ветки интерфейса.
- Logs не запускает защищённые чтения или clipboard/clear-действия после ухода экрана из STARTED;
  stop инвалидирует generation обоих read jobs, поэтому поздний root-ответ не публикует stale state.
- Control при каждом возврате в STARTED заново подтверждает authoritative runtime settings, не выполняет
  lifecycle/settings/update-вызовы под чужим modal-dialog, проверяет точный packet/update dialog перед
  подтверждением, не позволяет программно скрыть выполняющийся update и не записывает неизменённые
  autostart/packet значения с лишним restart.
- Toggle, settings, update-check/install и full rollback теперь захватывают один общий atomic action gate
  до запуска coroutine; устранена check-then-act гонка между ранее независимыми busy-флагами.
- Результат update-check привязан к dialog-state запуска: поздняя revalidation больше не возвращает
  закрытый пользователем update dialog и не заменяет другой modal flow.
- Strategies проверяет capability смены filter mode внутри ViewModel и отбрасывает повторный выбор уже
  активных strategy/filter, packet-count и debug-mode до записи конфигурации и restart.
- Presets также отбрасывает повторное применение уже активного файла и повторное включение categories
  mode внутри ViewModel, а не только через disabled-состояние Compose.
- Terminal-state APK больше не доверяет неизвестному состоянию: DNS очищает выбор после
  reset и требует reload после rollback failure, Control повторно сверяет runtime-настройки,
  а Logs и strategy order отдельно сообщают lifecycle-blocked outcome.
- Update-check busy gate освобождается ровно в одной `finally`-точке; устранено двойное
  освобождение, которое могло снять atomic-защиту с более новой проверки.
- Hostlist detail route больше не переносит привилегированный root-путь: навигация передаёт только
  проверенное имя файла, а repository выводит точный direct-child путь внутри доверенной границы.
- Сохранённые navigation back stacks больше не показывают устаревшее состояние модуля после возврата:
  Strategies, Presets, Hostlists, DNS и редакторы повторно читают authoritative source при входе в
  composition, при этом несохранённые черновики Config/Hosts/Hostlist остаются поверх новой baseline.
- Восстановление `SavedStateHandle` теперь удаляет неизвестные и несовместимые значения, канонизирует
  payload диалогов и не оставляет невидимый `pendingDialog`; восстановленные diagnostics повторно
  редактируются и в безопасном виде переписываются обратно в state.
- Защищённые текстовые, owner и recovery-журнальные чтения проверяют стабильность
  SHA-256 до/после потребления содержимого.
- Android backup закрыт явными deny-all правилами для legacy cloud backup и Android 12+
  cloud/device transfer, поскольку одного `allowBackup=false` недостаточно на части OEM.
- Единственный exported-компонент остаётся launcher Activity; FileProvider ограничен private
  update cache, а APK перед installer handoff повторно проверяет bounded-файл и точный режим `0600`.
- About открывает только четыре enum-owned HTTPS адреса, а экспорт Logs остаётся chooser-only
  после общего bounded/redacted преобразования.

### Производительность и устойчивость

- Большие редакторы не пересчитывают line count при несвязанных рекомпозициях.
- Control больше не ждёт первичную загрузку SharedPreferences на main dispatcher: состояние
  QUIC-banner читается на IO до публикации initial UI state.
- Strategy picker не копирует и не фильтрует каталог без изменения query/mode/order.
- Hostlist search получает count и bounded page за один проход файла.
- Hostlist/IP-set UI использует точный общий термин «записи» вместо неверного «домены».
- Поисковые строки Logs, Hostlist и strategy picker ограничены до попадания в Compose/SavedState;
  обновление конфигурации Strategies отменяет и инвалидирует незавершённый picker read.
- SavedStateHandle имеет общий ограниченный бюджет для editor drafts/baselines и
  strategy order recovery.
- Logs рендерятся и экспортируются bounded, с редактированием чувствительных данных.
- Reduced-motion использует один наблюдатель системного animator scale в корне темы;
  ContentResolver больше не читается во время main-thread composition: начальное и повторные
  чтения выполняются на IO с generation-cancellation, а до ответа motion fail-safe выключен.
  Экраны и компоненты получают согласованное значение без дублирующих observers.

### Release-контракты исходников

- Kotlin compiler, Android lint и custom-Lua compatibility настроены fail-on-warning.
- Release APK включает R8 code optimization и resource shrinking.
- Удалён широкий keep всей libsu; используются её собственные consumer rules.
- Android logcat-вызовы изолированы за `BuildConfig.DEBUG`; release не печатает
  root/firewall stack traces и status chatter.
- Удалён дублирующий application-scope root probe при старте; проверкой root владеет
  экранный controller/state flow.
- CI проверяет whitespace всего tracked-дерева и блокирует secret containers, PEM
  private keys, release debug-флаги и debug signing.
- Репозиторий фиксирует LF для текстовых и пакетируемых shell-файлов, CRLF только для
  `.bat`, а JAR/PNG отмечает как бинарные.
- Релиз остаётся fail-closed без signing secrets, ожидаемого signer SHA-256 и
  проверяемых APK/ZIP hash sidecars.

### Удалено

- XML View/Fragment/AppCompat/Fluent/Win11 UI, старые layouts/drawables/menu/navigation
  и obsolete compatibility helpers.
- Старые live bootstrap fallback-пути и дублирующий illustrative preview UI.
- Десять неиспользуемых shell helpers старых config/firewall/teardown/migration путей.

### Перед публикацией обязательно

- Запустить синхронизированный compile/lint/unit/shell/assemble graph.
- Проверить финальные подписи и SHA-256 APK/ZIP.
- Выполнить screenshot/accessibility и rooted-device lifecycle/update/uninstall matrix.
