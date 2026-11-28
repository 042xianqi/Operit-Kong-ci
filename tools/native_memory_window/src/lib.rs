//! 记忆抽取的会话切窗，与 Kotlin `ChatMemoryWindowPlanner` 同语义。
//!
//! 为什么下沉：重建记忆时要把整段会话按「用户轮次 + 助手回复」切成窗口。
//! 长会话有上万条消息，Kotlin 侧每轮都要构建多层 List 副本，GC 压力明显。
//! 这里只返回窗口边界索引，不复制消息内容。

pub const DEFAULT_WINDOW_MESSAGE_COUNT: usize = 32;
pub const MIN_WINDOW_MESSAGE_COUNT: usize = 8;
pub const MAX_WINDOW_MESSAGE_COUNT: usize = 48;

pub const SENDER_USER: u8 = 0;
pub const SENDER_AI: u8 = 1;
pub const SENDER_OTHER: u8 = 2;

#[derive(Clone, Debug)]
pub struct Msg {
    pub sender: u8,
    pub timestamp: i64,
    pub blank: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Window {
    pub indices: Vec<usize>,
    pub source_count: usize,
}

pub fn plan(
    messages: &[Msg],
    window_message_count: usize,
    range: Option<(i64, i64)>,
) -> Vec<Window> {
    let bounded = window_message_count
        .max(MIN_WINDOW_MESSAGE_COUNT)
        .min(MAX_WINDOW_MESSAGE_COUNT);
    let mut order: Vec<usize> = (0..messages.len()).collect();
    order.sort_by_key(|&i| messages[i].timestamp);
    let scoped: Vec<usize> = order
        .into_iter()
        .filter(|&i| match range {
            None => true,
            Some((from, to)) => messages[i].timestamp >= from && messages[i].timestamp <= to,
        })
        .collect();
    let mut windows: Vec<Window> = Vec::new();
    let mut pending_source: Vec<usize> = Vec::new();
    let mut pending_context: Vec<usize> = Vec::new();
    let mut current_turn_user: Option<usize> = None;
    for &idx in &scoped {
        let m = &messages[idx];
        match m.sender {
            SENDER_USER => {
                if m.blank { continue; }
                if !pending_source.is_empty() && pending_source.len() >= bounded - 1 {
                    emit(&mut windows, &mut pending_context, &mut pending_source, current_turn_user);
                }
                current_turn_user = Some(idx);
                pending_source.push(idx);
            }
            SENDER_AI => {
                if m.blank { continue; }
                let turn_user = match current_turn_user {
                    Some(v) => v,
                    None => continue,
                };
                if pending_source.len() >= bounded {
                    emit(&mut windows, &mut pending_context, &mut pending_source, current_turn_user);
                    pending_context.push(turn_user);
                }
                pending_source.push(idx);
            }
            _ => {}
        }
    }
    emit(&mut windows, &mut pending_context, &mut pending_source, current_turn_user);
    windows
}

fn emit(
    windows: &mut Vec<Window>,
    pending_context: &mut Vec<usize>,
    pending_source: &mut Vec<usize>,
    current_turn_user: Option<usize>,
) {
    if pending_source.is_empty() || current_turn_user.is_none() {
        return;
    }
    let mut indices = Vec::with_capacity(pending_context.len() + pending_source.len());
    indices.extend_from_slice(pending_context);
    indices.extend_from_slice(pending_source);
    windows.push(Window { indices, source_count: pending_source.len() });
    pending_source.clear();
    pending_context.clear();
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pairs(n: usize) -> Vec<Msg> {
        let mut v = Vec::new();
        let mut ts = 1i64;
        for _ in 0..n {
            v.push(Msg { sender: SENDER_USER, timestamp: ts, blank: false });
            ts += 1;
            v.push(Msg { sender: SENDER_AI, timestamp: ts, blank: false });
            ts += 1;
        }
        v
    }

    #[test]
    fn empty_input() {
        assert!(plan(&[], 32, None).is_empty());
    }

    #[test]
    fn ai_without_user_is_skipped() {
        let m = vec![Msg { sender: SENDER_AI, timestamp: 1, blank: false }];
        assert!(plan(&m, 32, None).is_empty());
    }

    #[test]
    fn blank_user_is_skipped() {
        let m = vec![
            Msg { sender: SENDER_USER, timestamp: 1, blank: true },
            Msg { sender: SENDER_AI, timestamp: 2, blank: false },
        ];
        assert!(plan(&m, 32, None).is_empty());
    }

    #[test]
    fn single_turn_makes_one_window() {
        let w = plan(&pairs(1), 32, None);
        assert_eq!(w.len(), 1);
        assert_eq!(w[0].source_count, 2);
    }

    #[test]
    fn window_size_is_clamped_low() {
        let w = plan(&pairs(4), 1, None);
        assert_eq!(w.len(), 1);
        assert_eq!(w[0].source_count, 8);
    }

    #[test]
    fn window_size_is_clamped_high() {
        let w = plan(&pairs(30), 999, None);
        assert_eq!(w.len(), 2);
    }

    #[test]
    fn time_range_filters_messages() {
        let m = pairs(20);
        let all = plan(&m, 8, None);
        let scoped = plan(&m, 8, Some((5, 20)));
        assert!(scoped.len() < all.len());
    }

    #[test]
    fn out_of_order_timestamps_are_sorted() {
        let m = vec![
            Msg { sender: SENDER_USER, timestamp: 9, blank: false },
            Msg { sender: SENDER_AI, timestamp: 3, blank: false },
            Msg { sender: SENDER_USER, timestamp: 5, blank: false },
            Msg { sender: SENDER_AI, timestamp: 7, blank: false },
        ];
        let w = plan(&m, 8, None);
        assert_eq!(w.len(), 1);
        // 排序后 ts=3 的 ai 在 user 之前，被跳过；其余进入同一窗口
        assert_eq!(w[0].source_count, 3);
    }

    #[test]
    fn group_chat_repeats_prompting_user() {
        let mut m = vec![Msg { sender: SENDER_USER, timestamp: 1, blank: false }];
        for t in 2..14 {
            m.push(Msg { sender: SENDER_AI, timestamp: t, blank: false });
        }
        let w = plan(&m, 8, None);
        assert_eq!(w.len(), 2);
        // 第二个窗口的首条应是重复的 user 提示
        assert_eq!(w[1].indices[0], 0);
    }
}
