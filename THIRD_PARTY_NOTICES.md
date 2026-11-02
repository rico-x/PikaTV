# Сторонние компоненты и уведомления

Актуально для PikaTV **0.4.4 / 15**, 2 ноября 2026 года. Лицензия оригинального кода — [MIT](LICENSE). Она не заменяет условия перечисленных компонентов.

## Проверенный состав runtime

В `releaseRuntimeClasspath` текущей версии разрешены **105 бинарных артефактов / 104 уникальные координаты**. Состав, версии, SHA-256, лицензии из POM, наследование лицензии родительского POM и найденные файлы уведомлений записаны в [dependency-inventory.json](licenses/dependency-inventory.json).

Это разрешённые зависимости до R8. Инвентарь не является полным SBOM содержимого итогового сжатого APK и не включает весь инструментарий сборки/тестирования. POM-метаданные проверены для всех перечисленных артефактов; дополнительные условия вложенных данных учитываются отдельно ниже.

Полные тексты обнаруженных лицензий/уведомлений из JAR/AAR и вложенных `classes.jar` сохранены в [runtime-notices.txt](licenses/runtime-notices.txt): 56 вхождений, 4 различных текста, одинаковые сгруппированы по SHA-256 с сохранением координат и путей. Это дополняет общие тексты лицензий ниже и сохраняет уведомления фактических артефактов.

| Компонент | Версия / группа | Правообладатели / проект | Лицензия и источник |
| --- | --- | --- | --- |
| AndroidX, Compose, Compose Material3, TV Material, Lifecycle, Media3 и другие AndroidX | Точные версии в инвентаре; Compose BOM 2026.02.01, TV Material 1.1.0, Media3 1.9.3 | The Android Open Source Project и участники AndroidX | [Apache-2.0](licenses/Apache-2.0.txt), объявления соответствующих Maven POM |
| Kotlin standard library | 2.3.21 | JetBrains и участники Kotlin | [Apache-2.0](licenses/Apache-2.0.txt), [исходный проект](https://github.com/JetBrains/kotlin) |
| kotlinx.coroutines | 1.10.2 | JetBrains и участники Kotlin | [Apache-2.0](licenses/Apache-2.0.txt), [лицензия версии](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/LICENSE.txt) |
| kotlinx.serialization, JetBrains annotations, JSpecify, Guava и остальные транзитивные компоненты | Точные координаты и выбранные версии в инвентаре | Соответствующие правообладатели компонентов | [Apache-2.0](licenses/Apache-2.0.txt), лицензии POM с учётом наследования |
| OkHttp | 4.12.0 | Square, Inc. и участники OkHttp | [Apache-2.0](licenses/Apache-2.0.txt); отдельно Public Suffix List ниже |
| Okio | Выбранная версия в инвентаре | Square, Inc. и участники Okio | [Apache-2.0](licenses/Apache-2.0.txt) |
| Coil | 3.4.0 | Coil Contributors | [Apache-2.0](licenses/Apache-2.0.txt), [лицензия версии](https://github.com/coil-kt/coil/blob/3.4.0/LICENSE.txt) |
| jsoup | 1.23.2 | Copyright © 2009–2026 Jonathan Hedley | [MIT, текст из фактического JAR](licenses/jsoup-MIT.txt), [проект jsoup](https://jsoup.org/) |

В каждом обновлении заново проверяется разрешённый граф: версия, указанная одной прямой зависимостью, может отличаться от выбранной Gradle транзитивной версии. Copyright отдельных файлов и дополнительные уведомления библиотек сохраняют силу.

## Public Suffix List в OkHttp

В `okhttp-4.12.0.jar` обнаружен `okhttp3/internal/publicsuffix/NOTICE`: `publicsuffixes.gz` создан из **Public Suffix List** и распространяется под **Mozilla Public License 2.0**. Сохранены [оригинальное NOTICE](licenses/OkHttp-PublicSuffix-NOTICE.txt), [полный текст MPL-2.0](licenses/MPL-2.0.txt) и [читаемые правила из поставляемого ресурса](licenses/publicsuffix-list-okhttp-4.12.0.txt).

Правила восстановлены из двух таблиц ресурса без изменения значений; исключения записаны с `!`. Комментарии исходного списка и взаимный порядок двух групп не восстановлены. Это явно обозначенная обработка для предоставления читаемой формы, а не заявление о побайтовом совпадении с исходным файлом авторов. Первичный проект и исходные списки: [publicsuffix.org](https://publicsuffix.org/list/), [publicsuffix/list](https://github.com/publicsuffix/list). Условия для исходной и исполняемой форм определяет [MPL-2.0](https://www.mozilla.org/en-US/MPL/2.0/).

Наличие такого набора данных не меняет лицензию оригинального кода PikaTV на MPL. Условия распространяемых компонентов и данных соблюдаются отдельно.

## Pikabu Return Minus

Источник сведений о формате рейтингов и настройках Pikabu — [Pikabu Return Minus](https://github.com/PyXiion/Pikabu-Return-Minus), версия 0.16.3, commit `c08c86c809109c6bdc14a43531d3693cdb9fc91b`. Сохранён [текст MIT с Copyright © 2023 PyXiion](licenses/Pikabu-Return-Minus-MIT.txt) и атрибуция исходного проекта.

## Инструментарий и тесты

JUnit 4.13.2 используется только для тестирования; [лицензия JUnit / EPL-1.0](licenses/JUnit-EPL-1.0.txt) сохранена отдельно. MockWebServer, AndroidX Test, Kotlin test-инструменты, Gradle, Android Gradle Plugin, Android SDK, JDK, Node.js, Python и CI-действия имеют собственные условия. Этот документ не утверждает, что весь инструментарий сборки входит в APK или полностью перечислен runtime-инвентарём.

При распространении дополнительных инструментов или тестовых APK их состав и лицензии учитываются отдельно.

## Медиа, эмодзи и товарные знаки

Посты, комментарии, изображения, видео, имена пользователей и знаки сервисов принадлежат соответствующим правообладателям. Лицензия PikaTV MIT не предоставляет прав на их повторное использование. [Скриншоты](docs/images/README.md) служат объяснению интерфейса; контент внутри не становится материалом под MIT.

Эмодзи отображаются системным шрифтом устройства. PikaTV не включает шрифт эмодзи либо исходный спрайт реакций Pikabu. Названия Android, Google TV, Pikabu, Яндекс, OpenAI и Anthropic используются для описания совместимости или инструментов, без заявления об одобрении.
