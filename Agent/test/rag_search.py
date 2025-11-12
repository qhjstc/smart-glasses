ES_NODES = "http://localhost:9200"

documents = [
    {"id": 1, "text": "How to start with Python programming.", "vector": [0.1, 0.2, 0.3]},
    {"id": 2, "text": "Advanced Python programming tips.", "vector": [0.1, 0.3, 0.4]},
    # More documents...
]

from elasticsearch import Elasticsearch

es = Elasticsearch(
    hosts=ES_NODES,
)
for doc in documents:
    es.index(index="documents", id=doc['id'], document={"text": doc['text']})