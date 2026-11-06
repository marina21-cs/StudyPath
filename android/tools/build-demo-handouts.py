"""Original StudyPath presentation handouts; regenerate with reportlab 4.x."""
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.lib.colors import HexColor
from reportlab.lib.utils import simpleSplit
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
pdfmetrics.registerFont(TTFont("DemoText", str(Path(__file__).resolve().parents[1]/"app/src/main/res/font/geist.ttf")))
pdfmetrics.registerFont(TTFont("DemoBold", str(Path(__file__).resolve().parents[1]/"app/src/main/res/font/nunito_bold.ttf")))

out=Path(__file__).resolve().parents[1]/'app/src/main/assets/demo'
out.mkdir(parents=True,exist_ok=True)
items=[
('calculus','Advanced Calculus','Laplace transforms',
'A Laplace transform represents a function of time using a new variable s. Linearity means that a sum can be transformed one term at a time. Constant multipliers stay outside the transform.',
'For s > 0, L{1} = 1/s and L{t} = 1/s^2. By linearity, L{2 + 3t} = 2/s + 3/s^2. Check each term before combining the result.',
'Find L{4 + 2t}, for s > 0.','4/s + 2/s^2. Apply linearity to the constant term and the term containing t.'),
('structures','Data Structures','Queues and stacks',
'A queue is a collection that removes items in first-in, first-out order. Enqueue adds an item to the back of a queue. Dequeue removes the item at the front. A stack removes the most recently added item first.',
'Queue trace: enqueue A, enqueue B, enqueue C. The queue is [A, B, C], with A at the front. Dequeue returns A and leaves [B, C]. A stack holding A, B, C with C on top would pop C.',
'Enqueue red, blue and green, then dequeue twice. What remains?','Green remains. Red leaves first, followed by blue.'),
('databases','Database Systems','Tables and relationships',
'A relational database organizes records into tables. A primary key identifies each record uniquely. A foreign key references a key in another table. An enrollment table connects students to subjects.',
'Student(student_id, name), Subject(subject_id, title), and Enrollment(student_id, subject_id) separate the three kinds of facts. A student can have many enrollment rows without repeating their name in each one.',
'Where should a student name be stored in this design?','In the Student table. Enrollment refers to the student through student_id.'),
('physics','General Physics','Forces and acceleration',
'Net force is the vector sum of forces acting on an object. In an inertial frame, net force equals mass times acceleration. Acceleration is the rate at which velocity changes.',
'A 2 kg cart has a 10 N force to the right and a 4 N force to the left. Choose right as positive. Net force = 10 - 4 = 6 N. Acceleration = 6 / 2 = 3 m/s^2 to the right.',
'A 3 kg object experiences a net force of 12 N to the left. Find its acceleration.','4 m/s^2 to the left. Divide net force by mass and keep the direction.'),
('communication','Purposive Communication','Audience and purpose',
'Audience refers to the people who receive a message. Purpose is the result the writer wants the message to achieve. Clear communication chooses details and tone that suit both audience and purpose.',
'For classmates: Please bring your draft to our 2 PM peer review. For a new learner: Bring one paragraph you want feedback on. We will meet at 2 PM to discuss clarity and organization.',
'Why might a message to a new learner need more explanation?','The learner may not share the background knowledge of the class. Explain unfamiliar tasks and terms.')]
for key,subject,title,concept,example,question,answer in items:
    c=canvas.Canvas(str(out/f'{key}.pdf'),pagesize=(420,594),invariant=1)
    c.setTitle(title);c.setAuthor('StudyPath - original fictional demo material')
    def page(n,heading,sections):
        c.setFillColor(HexColor('#F2F5FC'));c.rect(0,0,420,594,fill=1,stroke=0)
        c.setFillColor(HexColor('#315CB5'));c.setFont('DemoBold',12);c.drawString(30,557,'studypath. / SAMPLE HANDOUT')
        c.setFillColor(HexColor('#203554'));c.setFont('DemoText',10);c.drawString(30,533,subject)
        y=495
        for line in simpleSplit(heading,'DemoBold',24,360):
            c.setFont('DemoBold',24);c.drawString(30,y,line);y-=29
        y-=14
        for label,body in sections:
            c.setFont('DemoBold',13);c.drawString(30,y,label);y-=22
            for line in simpleSplit(body,'DemoText',12,350):
                c.setFont('DemoText',12);c.drawString(30,y,line);y-=18
            y-=22
        assert y>70,(key,n,y)
        c.setFillColor(HexColor('#526580'));c.setFont('DemoText',8)
        c.drawString(30,40,'Original presentation sample. Not an approved course module.')
        c.drawString(30,26,f'Use the PDF reviewer to practice and check the source.  /  {n} of 2')
        c.showPage()
    page(1,title,[('Understand',concept),('Worked example',example)])
    page(2,'Try it, then check.',[('Practice question',question),('Answer and reasoning',answer),('Reflect','Explain the steps in your own words. Create a review card and return to page 1 when you need the original explanation.')])
    c.save()
print('Created five original two-page PDFs:',out)
