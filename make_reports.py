"""Generate the two homework reports from the checked-in benchmark data."""
from pathlib import Path
import csv
from collections import defaultdict
from statistics import median
from xml.sax.saxutils import escape

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak, Image

ROOT = Path(__file__).resolve().parent
FONT_DIR = Path('C:/Windows/Fonts')
pdfmetrics.registerFont(TTFont('Report', str(FONT_DIR / 'arial.ttf')))
pdfmetrics.registerFont(TTFont('ReportBold', str(FONT_DIR / 'arialbd.ttf')))
pdfmetrics.registerFontFamily('Report', normal='Report', bold='ReportBold', italic='Report', boldItalic='ReportBold')
styles = getSampleStyleSheet()
styles.add(ParagraphStyle('BodyRu', fontName='Report', fontSize=10.5, leading=15, spaceAfter=8))
styles.add(ParagraphStyle('TitleRu', fontName='ReportBold', fontSize=18, leading=23, spaceAfter=12))
styles.add(ParagraphStyle('HeadingRu', fontName='ReportBold', fontSize=12, leading=16, spaceBefore=10, spaceAfter=7))
styles.add(ParagraphStyle('SmallRu', fontName='Report', fontSize=8, leading=11, spaceAfter=6))
styles.add(ParagraphStyle('CellRu', fontName='Report', fontSize=8.5, leading=12))


def p(text, style='BodyRu'):
    return Paragraph(text, styles[style])


def title(number, name):
    return [p('Параллельное программирование', 'TitleRu'),
            p(f'Домашняя работа №{number}. {name}', 'HeadingRu'),
            p('Выполнил: Деревянкин Тимофей<br/>Группа: 24-по-1<br/>Дата: 08.10.2026'),
            Spacer(1, 6)]


def table(rows, widths):
    t = Table([[p(escape(str(cell)), 'CellRu') for cell in row] for row in rows],
              colWidths=widths, repeatRows=1, hAlign='LEFT')
    t.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#e7edf3')),
        ('ROWBACKGROUNDS', (0, 1), (-1, -1), [colors.white, colors.HexColor('#f7f9fb')]),
        ('GRID', (0, 0), (-1, -1), 0.4, colors.HexColor('#bdc8d2')),
        ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('LEFTPADDING', (0, 0), (-1, -1), 6), ('RIGHTPADDING', (0, 0), (-1, -1), 6),
        ('TOPPADDING', (0, 0), (-1, -1), 6), ('BOTTOMPADDING', (0, 0), (-1, -1), 6),
    ]))
    return t


def footer(canvas, doc):
    canvas.setFont('Report', 8)
    canvas.setFillColor(colors.HexColor('#596579'))
    canvas.drawString(44, 28, 'ПП | 24-по-1 | Деревянкин Тимофей')
    canvas.drawRightString(A4[0] - 44, 28, str(doc.page))


def build(path, story):
    doc = SimpleDocTemplate(str(path), pagesize=A4, rightMargin=44, leftMargin=44,
                            topMargin=38, bottomMargin=44, title=path.parent.name + ' - отчёт',
                            author='Деревянкин Тимофей, 24-по-1')
    doc.build(story, onFirstPage=footer, onLaterPages=footer)
    print(path)


def mandelbrot_report():
    story = title(3, 'Множество Мандельброта')
    story += [p('Цель и постановка задачи', 'HeadingRu'),
              p('Рассчитать для каждого пикселя принадлежность соответствующей точки комплексной плоскости '
                'множеству Мандельброта с использованием нескольких потоков. Вывод изображения опционален; '
                'в реализации предусмотрены PNG и ASCII.'),
              p('Алгоритм', 'HeadingRu'),
              p('Для центра каждого пикселя выбирается c = cx + i·cy из области '
                '[-2.5; 1.0] × [-1.25; 1.25]. Итерации начинаются с z = 0; '
                'на каждом шаге вычисляется z = z² + c. Выход при |z|² &gt; 4 означает, что точка вне множества. '
                'Если выход не обнаружен за 1000 итераций, сохраняется -1. '
                'Это конечное приближение принадлежности, особенно около границы.'),
              p('Организация потоков', 'HeadingRu'),
              p('FixedThreadPool содержит шесть работников. Каждый берёт индекс следующей строки '
                'через AtomicInteger, рассчитывает все её пиксели и берёт новую строку. '
                'Так занятые строки не дублируются, а свободный поток может продолжить работу, '
                'не ожидая завершения постоянного блока. Записи идут в разные строки массива, '
                'поэтому пиксельные блокировки не требуются. Future.get() ожидает работников '
                'и обеспечивает видимость результатов.'),
              p('Результат пробного запуска', 'HeadingRu'),
              table([['Параметр', 'Результат'], ['Размер / предел итераций', '1000 × 720 / 1000'],
                     ['Параллельный расчёт, 6 работников', '79.102 мс'],
                     ['Последовательный расчёт', '413.857 мс'], ['Ускорение в этом запуске', '5.23'],
                     ['Не вышли за радиус 2', '124278 из 720000 пикселей'],
                     ['Сверка каждого пикселя', 'Полное совпадение']], [285, 222]),
              Spacer(1, 8),
              p('Среда: Intel Core i5-11400F, 6 физических и 12 логических ядер, Windows 11, '
                'Temurin 21.0.12.1. Перед расчётом выполнен короткий прогрев. '
                'Запись PNG исключена из замера. Один запуск не является статистической оценкой ускорения.', 'SmallRu'),
              PageBreak(), p('Рассчитанное изображение', 'HeadingRu'),
              Image(str(ROOT / 'practice3/mandelbrot.png'), width=490, height=352.8), Spacer(1, 8),
              p('Чёрные пиксели не вышли за радиус 2 за установленное число шагов; '
                'цвет снаружи зависит от количества итераций до выхода.', 'SmallRu'),
              p('Проверка корректности', 'HeadingRu'),
              p('Проверены точки 0, -1, -2 (не выходят), 1 и 2 (выходят). '
                'Отдельно проверен выход ровно на последней итерации. Для сеток 101×43, 7×3 и 1×1 '
                'при 1, 2, 6 и 8 потоках каждый результат сравнивается с последовательной версией. '
                'Включён случай, когда потоков больше, чем строк.'),
              p('Запуск', 'HeadingRu'),
              p('java -jar Mandelbrot.jar --verify --png mandelbrot.png<br/>'
                'java -jar Mandelbrot.jar --check', 'SmallRu'),
              p('Вывод', 'HeadingRu'),
              p('Каждый пиксель рассчитан параллельно, результат совпадает с последовательным алгоритмом. '
                'Динамическое распределение строк уменьшает простой при неодинаковой стоимости расчёта '
                'разных частей изображения. Для очень маленькой сетки расходы на запуск потоков '
                'могут оказаться больше выигрыша от параллельности.')]
    build(ROOT / 'practice3/report.pdf', story)


def executors_report():
    groups = defaultdict(list)
    with (ROOT / 'practice4/benchmark.csv').open(encoding='utf-8', newline='') as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 72 and all(int(r['completed']) == int(r['tasks']) == 240 for r in rows)
    for row in rows:
        groups[row['workload'], row['distribution'], row['executor']].append(row)
    kinds = ['THREAD_PER_TASK', 'ROUND_ROBIN', 'WORK_STEALING', 'FIXED_THREAD_POOL']

    def results(field):
        data = [['Режим / распределение', 'ThreadPerTask', 'RoundRobin', 'WorkStealing', 'FixedPool']]
        for workload in ['SLEEP', 'CPU']:
            for distribution in ['UNIFORM', 'PERIODIC', 'PARETO']:
                data.append([workload + ' / ' + distribution] +
                            [f"{median(float(r[field]) for r in groups[workload, distribution, kind]):.3f}"
                             for kind in kinds])
        return table(data, [155, 92, 85, 95, 80])

    story = title(4, 'Сравнение исполнителей задач')
    story += [p('Цель и реализация', 'HeadingRu'),
              p('Реализовать обёртку FixedThreadPool, исполняющую ShutdownableExecutor, '
                'сравнить её с ThreadPerTask, RoundRobin и WorkStealing. Повторить замеры после '
                'замены sleep на вычислительную функцию blackHole(difficulty).'),
              p('Обёртка делегирует execute в Executors.newFixedThreadPool; shutdown закрывает '
                'приём и ждёт awaitTermination. RoundRobin распределяет задачи по отдельным очередям. '
                'WorkStealing при пустой своей очереди берёт задачу с конца чужой. '
                'Счётчик pending учитывает ожидающие и выполняемые задачи, чтобы shutdown '
                'не завершал работников раньше времени. ThreadPerTask создаёт отдельный платформенный поток.'),
              p('Условия эксперимента', 'HeadingRu'),
              p('Windows 11, Intel i5-11400F (6 физических / 12 логических ядер), Temurin 21.0.12.1. '
                '240 задач, 6 работников в каждом пуле, средняя сложность 4, сумма 960, seed=42. '
                'Для каждого случая: 1 прогрев и 3 измерения, результат - медиана. '
                'Вместо 100000 задач выбрано 240, чтобы можно было запустить ThreadPerTask. '
                'Порядок исполнителей перемешивается, массив сложности для них одинаков.'),
              p('UNIFORM: равномерное распределение. PERIODIC: каждая шестая задача тяжёлая, '
                'остальные нулевые. PARETO: редкие длинные задачи, параметр формы 1.5, '
                'ограничение выбросов. Все массивы нормированы до одинаковой суммы. '
                'В SLEEP единица - миллисекунда; в CPU единица - 200000 шагов.'),
              p('Медианы полного времени, мс', 'HeadingRu'), results('total_ms'), Spacer(1, 8),
              p('Полное время включает создание исполнителя, подачу задач и ожидание завершения. '
                'Подготовка задач, печать и запись CSV исключены. Все 72 измеряемых запуска '
                'завершили ровно по 240 задач без зарегистрированных ошибок.', 'SmallRu'),
              PageBreak(), p('Линейная вычислительная нагрузка', 'HeadingRu'),
              p('blackHole(difficulty) выполняет точно 200000 × difficulty итераций '
                'с зависимыми целочисленными операциями сдвига, умножения и сложения. '
                'Сложность O(difficulty). Результат записывается в volatile-поле один раз после цикла: '
                'это препятствует удалению вычисления JIT. Переполнение long внутри смешивания намеренное; '
                'отрицательная сложность запрещена, переполнение счётчика итераций проверяется multiplyExact.'),
              p('Медианы времени подачи, мс', 'HeadingRu'), results('submission_ms'), Spacer(1, 8),
              p('Время подачи также включает конструктор исполнителя. Задачи могут выполняться '
                'параллельно с подачей, поэтому это не отдельная непересекающаяся стадия.', 'SmallRu'),
              p('Результаты и выводы', 'HeadingRu'),
              p('При PERIODIC тяжёлые задачи RoundRobin оказываются в одной очереди. FixedPool '
                'быстрее него примерно в 5.7 раза для sleep и в 5.8 раза для CPU. '
                'Общая очередь и кража задач позволяют занять простаивающих работников. '
                'FixedPool быстрее двух самодельных пулов во всех шести случаях данной серии.'),
              p('ThreadPerTask оказался быстрее на этой небольшой выборке: ожидания перекрываются, '
                'а вычисления могут занять больше шести потоков и использовать логические ядра. '
                'Количество работающих потоков не выровнено с пулами. Это не доказывает эффективность '
                'создания 100000 потоков. При PARETO отдельная длинная задача остаётся неделимой '
                'и может определять время завершения независимо от очередей.'),
              p('Абсолютные времена SLEEP и CPU соответствуют разным типам работы: сложность CPU '
                'не задаёт длительность в миллисекундах. Это учебный стенд, не JMH; три повтора '
                'не исключают влияние JIT, планировщика, разрешения таймеров Windows и фоновых программ.'),
              p('Проверки и запуск', 'HeadingRu'),
              p('Самопроверка контролирует выполнение ровно один раз, отказ после закрытия, '
                'пустой и повторный shutdown, конкурирующие execute/shutdown, сохранение прерывания '
                'и нормировку распределений. Проверки прошли для всех четырёх исполнителей.'),
              p('java -jar WorkStealing.jar --check<br/>java -jar WorkStealing.jar --csv benchmark.csv', 'SmallRu'),
              p('Документация: Oracle Java SE 21, ExecutorService и Executors.<br/>'
                '<link href="https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html">'
                'docs.oracle.com: ExecutorService</link>; '
                '<link href="https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html">Executors</link>.', 'SmallRu')]
    build(ROOT / 'practice4/report.pdf', story)


if __name__ == '__main__':
    mandelbrot_report()
    executors_report()
