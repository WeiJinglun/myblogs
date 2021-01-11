/**
 * @author WeiJinglun
 * @date 2020.07.09
 **/
public class Soulte {
    static class Solution {
        public Solution() {
        }

        public int respace(String[] dictionary, String sentence) {
            int n = sentence.length();
            Trie root = new Trie();
            for (String word : dictionary) {
                root.insert(word);
            }
            int result = 0;
            int flag = 0;
            Trie current = root;
            for (int i = n - 1; i >= 0; --i) {
                char c = sentence.charAt(i);
                int t = c - 'a';
                flag++;
                if (current.next[t] == null) {
                    result = result + flag;
                    flag = 0;
                    current = root;
                } else if (current.next[t].isEnd) {
                    flag = 0;
                    current = root;
                } else {
                    current = current.next[t];
                }
            }
            return result + flag;
        }
    }

    static class Trie {
        public Trie[] next;
        public boolean isEnd;

        public Trie() {
            next = new Trie[26];
            isEnd = false;
        }

        public void insert(String s) {
            Trie curPos = this;

            for (int i = s.length() - 1; i >= 0; --i) {
                int t = s.charAt(i) - 'a';
                if (curPos.next[t] == null) {
                    curPos.next[t] = new Trie();
                }
                curPos = curPos.next[t];
            }
            curPos.isEnd = true;
        }
    }

    public static void main(String[] args) {
        Solution solution = new Solution();
        System.out.println(solution.respace(new String[]{"looked", "just", "like", "her", "brother"}, "jesslookedjustliketimherbrother"));
    }
}
