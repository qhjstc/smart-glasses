import json
import time
from dashscope import Generation
import dashscope

dashscope.api_key = 'sk-e05e5076e72e493998428e2d770e7a11'

# ========== 1️⃣ 定义基础 Persona 数据 (模拟 Synthetic‑Persona‑Chat 示例) ==========
base_personas = [
    {
        "persona_a": "I am a university student studying computer science. I'm curious and polite.",
        "persona_b": "I am a professor who supervises graduate students, structured and encouraging."
    },
    {
        "persona_a": "I am a nurse working night shifts, empathetic and friendly.",
        "persona_b": "I am a doctor at the same hospital, focused and professional."
    }
]

# ========== 2️⃣ 定义社会因素 (social factors) ==========
social_factors = [
    {"relation": "superior-subordinate", "formality": "formal", "context": "office"},
    {"relation": "peer-peer", "formality": "informal", "context": "cafe"},
    {"relation": "stranger", "formality": "neutral", "context": "conference"}
]

# ========== 3️⃣ 调用 Qwen 模型的封装函数 ==========
def qwen_generate(prompt: str, retry: int = 2) -> str:
    """更稳健的 Qwen 调用封装，兼容多种返回结构"""
    for attempt in range(retry):
        try:
            response = Generation.call(
                model="qwen-max",
                prompt=prompt,
                result_format="message"
            )

            # --- Debug 用：查看原始结构 ---
            # print(json.dumps(response, indent=2, ensure_ascii=False))

            if not response:
                raise ValueError("Empty response from DashScope")

            # Case 1: 标准 text 字段
            if isinstance(response, dict):
                output = response.get("output", {})
                if isinstance(output, dict):
                    # 优先从 text 取结果
                    text = output.get("text")
                    if isinstance(text, str) and text.strip():
                        return text.strip()

                    # Case 2: 从 message choice 里提取
                    choices = output.get("choices")
                    if choices and isinstance(choices, list):
                        msg = choices[0].get("message", {})
                        content = msg.get("content")
                        if content and isinstance(content, str):
                            return content.strip()

            # Case 3: fallback - 打印整个对象
            print("⚠️ 未找到文本字段，返回原始数据结构")
            return json.dumps(response, ensure_ascii=False)

        except Exception as e:
            print(f"⚠️ Qwen 调用失败（第 {attempt+1} 次）: {e}")
            time.sleep(1)

    return "[ERROR: EMPTY_RESPONSE]"

# ========== 4️⃣ Prompt 模板：两个角色 ==========
def compose_prompt(persona, partner_persona, factors, role="user"):
    role_desc = "You are the AR glasses user" if role == "user" else "You are the conversation partner"
    return f"""
{role_desc} engaged in a {factors['formality']} conversation with a {factors['relation']} at a {factors['context']}.
Your persona: {persona}
Your partner's persona: {partner_persona}
Speak naturally, stay in character, under 50 words.
"""

# ========== 5️⃣ 双 LLM 对话模拟 ==========
def simulate_conversation(p_a, p_b, factors, rounds=4):
    conv = []
    user_prompt = compose_prompt(p_a, p_b, factors, "user")
    partner_prompt = compose_prompt(p_b, p_a, factors, "partner")
    last_reply = ""

    for i in range(rounds):
        # User Agent
        user_input = user_prompt + (f"\nPartner said: {last_reply}" if last_reply else "")
        user_resp = qwen_generate(user_input)
        conv.append({"speaker": "User", "text": user_resp})

        # Partner Agent
        partner_input = partner_prompt + f"\nUser said: {user_resp}"
        partner_resp = qwen_generate(partner_input)
        conv.append({"speaker": "Partner", "text": partner_resp})

        last_reply = partner_resp or last_reply
        print(f"🗨️ Round {i+1} done.")
    return conv

# ========== 6️⃣ 第三模型生成社交建议 ==========
def generate_social_advice(conversation, factors):
    joined_dialogue = "\n".join([f"{c['speaker']}: {c['text']}" for c in conversation])
    prompt = f"""
You are a social behavior assistant.
Given the following conversation and social context,
provide one concise behavioral tip for the AR glasses user
(e.g., tone, body language, response timing).

Context: {factors}
Conversation:
{joined_dialogue}
"""
    return qwen_generate(prompt)

# ========== 7️⃣ 主流程：构建社交缓存 ==========
def build_social_cache():
    social_cache = []
    for idx, personas in enumerate(base_personas):
        for jdx, factors in enumerate(social_factors):
            print(f"\n=== 模拟场景 [{idx+1}-{jdx+1}] ===")
            dialogue = simulate_conversation(personas["persona_a"], personas["persona_b"], factors)
            advice = generate_social_advice(dialogue, factors)
            entry = {
                "context_key": dialogue[-1]["text"] if dialogue else "",
                "social_advice": advice,
                "social_factors": factors,
                "dialogue": dialogue
            }
            social_cache.append(entry)
    return social_cache

# ========== 8️⃣ 执行并保存结果 ==========
if __name__ == "__main__":
    data = build_social_cache()
    with open("socialmind_qwen_cache.json", "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    print(f"\n✅ 已生成 {len(data)} 条社交对话样本，结果保存在 socialmind_qwen_cache.json 中。")