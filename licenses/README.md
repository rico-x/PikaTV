# Лицензии и происхождение уведомлений

Обновлено 1 ноября 2026 года для PikaTV 0.4.3. Основной текст MIT проекта — [../LICENSE](../LICENSE); сводный перечень — [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

| Файл | Источник и назначение |
| --- | --- |
| [Apache-2.0.txt](Apache-2.0.txt) | [Канонический текст Apache](https://www.apache.org/licenses/LICENSE-2.0.txt); применяется к указанным Apache-компонентам |
| [jsoup-MIT.txt](jsoup-MIT.txt) | `META-INF/jsoup/LICENSE` из `jsoup-1.22.1.jar`, с copyright Jonathan Hedley |
| [Pikabu-Return-Minus-MIT.txt](Pikabu-Return-Minus-MIT.txt) | [LICENSE исследованного commit](https://github.com/PyXiion/Pikabu-Return-Minus/blob/c08c86c809109c6bdc14a43531d3693cdb9fc91b/LICENSE), Copyright © 2023 PyXiion |
| [MPL-2.0.txt](MPL-2.0.txt) | [Канонический текст Mozilla](https://www.mozilla.org/media/MPL/2.0/index.txt), для Public Suffix List внутри OkHttp |
| [OkHttp-PublicSuffix-NOTICE.txt](OkHttp-PublicSuffix-NOTICE.txt) | Без изменений из `okhttp3/internal/publicsuffix/NOTICE` фактического `okhttp-4.12.0.jar` |
| [publicsuffix-list-okhttp-4.12.0.txt](publicsuffix-list-okhttp-4.12.0.txt) | Читаемые правила и исключения, восстановленные из поставляемого `publicsuffixes.gz`; происхождение и преобразование указаны в заголовке |
| [JUnit-EPL-1.0.txt](JUnit-EPL-1.0.txt) | [LICENSE-junit.txt версии 4.13.2](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt); относится к тестовому инструменту, не заявляется runtime-лицензией приложения |
| [dependency-inventory.json](dependency-inventory.json) | Разрешённые runtime-артефакты и лицензии POM, текущей версии; 105 артефактов, 104 уникальные координаты |
| [runtime-notices.txt](runtime-notices.txt) | Полные найденные тексты из JAR/AAR и вложенных `classes.jar`: 53 вхождения, 3 уникальных текста; одинаковые объединены с сохранением координат, путей и SHA-256 |

Общие тексты лицензий не заменяют применимые copyright/NOTICE и условия отдельных ресурсов. Машиночитаемый инвентарь фиксирует зависимости перед R8; это не полный анализ каждого файла итогового APK. Вложенный MPL-ресурс OkHttp указан отдельно от Apache-лицензии самой библиотеки.

Перед распространением изменённой сборки заново сверить граф, вложенные уведомления и комплект дистрибутива. Не заменять этот каталог одной декларацией «все зависимости MIT».
