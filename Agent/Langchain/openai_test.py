from langchain_openai import OpenAI

# 初始化 LLM
# 注意: text-davinci-003 已被 OpenAI 弃用，建议使用 gpt-3.5-turbo-instruct
llm = OpenAI(model_name="gpt-3.5-turbo-instruct", max_tokens=1024)

# 调用
response = llm.invoke("怎么评价人工智能")
print(response)