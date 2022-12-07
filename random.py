import uuid

file_name = str(uuid.uuid1())
file_text = str(uuid.uuid1())

with open(file_name, 'x') as file:

    file.write(file_text)