import json
import os
from dashscope import Generation
import dashscope

# 设置API Key（已固定）
dashscope.api_key = 'sk-e05e5076e72e493998428e2d770e7a11'

# 初始化对话历史（包含系统提示）
messages = [
    {"role": "system", "content": "You are a helpful assistant."},
]

print("AI助手已启动！输入 'exit' 退出对话。")
print("-" * 50)

while True:
    # 获取用户输入
    user_input = input("用户: ")
    
    # 检查是否退出
    if user_input.lower() in ['exit', 'quit', 'bye']:
        print("AI助手: 再见！")
        break
    
    # 添加用户消息到对话历史
    messages.append({"role": "user", "content": user_input})
    
    # 调用模型
    response = Generation.call(
        model="qwen-plus",
        messages=messages,
        result_format="message",
    )
    
    # 检查响应状态
    if response.status_code == 200:
        # 提取AI的回答（从第一个choice中获取）
        ai_response = response.output.choices[0].message.content
        
        # 打印AI的回答
        print(f"AI助手: {ai_response}")
        
        # 将AI的回答添加到对话历史
        messages.append({"role": "assistant", "content": ai_response})
    else:
        # 处理错误
        print(f"错误: HTTP {response.status_code}, 错误码: {response.code}, 信息: {response.message}")
        print("请参考文档: https://help.aliyun.com/zh/model-studio/developer-reference/error-code")
        print("-" * 50)