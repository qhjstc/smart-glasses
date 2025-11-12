import nltk
from nltk.tokenize import sent_tokenize

nltk.download('punkt_tab')  # Ensure the punkt tokenizer is downloaded

def sliding_window(text, window_size=3):
    """
    Generate text chunks using a sliding window approach.

    Args:
    text (str): The input text to chunk.
    window_size (int): The number of sentences per chunk.

    Returns:
    list of str: A list of text chunks.
    """
    sentences = sent_tokenize(text)
    return [' '.join(sentences[i:i+window_size]) for i in range(len(sentences) - window_size + 1)]

# Example usage
text = "This is the first sentence. Here comes the second sentence. And here is the third one. Finally, the fourth sentence."
chunks = sliding_window(text, window_size=3)
for chunk in chunks:
    print(chunk)
    print("-----")
    # here, you can convert the chunk to embedding vector
    # and, save it to a vector database