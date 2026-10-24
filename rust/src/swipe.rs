use serde::Deserialize;
use std::sync::OnceLock;

static WORD_LIST: &str = include_str!("../data/words.txt");

#[derive(Clone, Debug, Deserialize)]
pub struct Key {
    pub c: char,
    pub x: f32,
    pub y: f32,
}

struct Word {
    text: &'static str,
    /// 0.0 = most common, 1.0 = least common in the bundled list.
    prior: f32,
}

fn words() -> &'static Vec<Word> {
    static WORDS: OnceLock<Vec<Word>> = OnceLock::new();
    WORDS.get_or_init(|| {
        let total = WORD_LIST.lines().count().max(1) as f32;
        WORD_LIST
            .lines()
            .enumerate()
            .filter_map(|(index, line)| {
                let text = line.trim();
                if text.len() >= 2
                    && text.len() <= 15
                    && text.chars().all(|c| c.is_ascii_lowercase())
                {
                    Some(Word {
                        text,
                        prior: index as f32 / total,
                    })
                } else {
                    None
                }
            })
            .collect()
    })
}

/// Sequence of keys the finger passed over, with consecutive duplicates removed.
pub fn crossed_letters(path: &[[f32; 2]], keys: &[Key]) -> Vec<char> {
    let mut out = Vec::new();
    for point in path {
        let mut best: Option<(&Key, f32)> = None;
        for key in keys {
            let dx = point[0] - key.x;
            let dy = point[1] - key.y;
            let distance = dx * dx + dy * dy;
            if best.map(|(_, d)| distance < d).unwrap_or(true) {
                best = Some((key, distance));
            }
        }
        if let Some((key, _)) = best {
            if out.last() != Some(&key.c) {
                out.push(key.c);
            }
        }
    }
    out
}

/// Scores a word by how closely, and in what order, the swipe passes its
/// letter centers. Extra path segments don't matter, which matches how people
/// cut corners between keys.
fn score_word(
    word: &str,
    path: &[[f32; 2]],
    centers: &[[f32; 2]; 26],
    present: &[bool; 26],
    scale: f32,
    crossed: &[char],
) -> Option<f32> {
    let letters: Vec<char> = word.chars().collect();
    if letters.len() < 2 {
        return None;
    }

    // 1. Sequence: the word's letters must appear in order in the keys the
    //    finger crossed. Extra keys are tolerated but penalised.
    let mut index = 0usize;
    let mut skipped = 0usize;
    let mut missing = 0usize;
    let mut previous: Option<char> = None;
    for &letter in &letters {
        if let Some(offset) = crossed[index..].iter().position(|&c| c == letter) {
            skipped += offset;
            index += offset + 1;
        } else if previous == Some(letter) {
            // Double letters are usually collapsed by the gesture path.
        } else {
            missing += 1;
        }
        previous = Some(letter);
    }
    skipped += crossed.len().saturating_sub(index);

    if missing > (letters.len() / 4).max(1) {
        return None;
    }

    // 2. Spatial: how close the path comes to each letter centre, in order.
    let mut distance_sum = 0f32;
    let mut last_index = 0usize;
    let mut last_used: Option<char> = None;
    for &letter in &letters {
        let numeric = letter as u32;
        if !(b'a' as u32..=b'z' as u32).contains(&numeric) {
            return None;
        }
        let numeric = (numeric - b'a' as u32) as usize;
        if !present[numeric] {
            return None;
        }
        let center = centers[numeric];

        let mut best_distance = f32::INFINITY;
        let mut best_index = last_index;
        for (position, point) in path.iter().enumerate().skip(last_index) {
            let dx = point[0] - center[0];
            let dy = point[1] - center[1];
            let distance = dx * dx + dy * dy;
            if distance < best_distance {
                best_distance = distance;
                best_index = position;
            }
        }
        if !best_distance.is_finite() {
            return None;
        }
        distance_sum += best_distance.sqrt();
        if last_used != Some(letter) {
            last_index = best_index;
        }
        last_used = Some(letter);
    }

    // Sequence cost is normalised by the gesture length so the threshold
    // behaves the same for short and long swipes.
    let sequence = missing as f32 * 0.5
        + (skipped as f32 / crossed.len().max(1) as f32) * 1.5;
    let spatial = distance_sum / letters.len() as f32;
    let start_penalty = if letters.first() != crossed.first() { 0.035 } else { 0.0 };
    let end_penalty = if letters.last() != crossed.last() { 0.03 } else { 0.0 };
    let length_penalty =
        (letters.len() as f32 - crossed.len() as f32).abs() / 10.0 * 0.05;
    // Prefer words that explain the whole gesture.
    let unused = (path.len().saturating_sub(last_index + 1)) as f32 / path.len() as f32;
    Some(sequence + spatial + start_penalty + end_penalty + length_penalty + unused * 0.07)
}

pub fn decode(path: &[[f32; 2]], keys: &[Key], extra: &[String]) -> Option<(String, f32)> {
    if path.len() < 2 || keys.len() < 2 {
        return None;
    }

    let crossed = crossed_letters(path, keys);
    if crossed.len() < 2 {
        return None;
    }

    // Build a direct letter -> center lookup and normalize by keyboard width.
    let mut centers = [[0f32; 2]; 26];
    let mut present = [false; 26];
    let mut min_x = f32::INFINITY;
    let mut max_x = f32::NEG_INFINITY;
    for key in keys {
        let index = key.c as u32;
        if !(b'a' as u32..=b'z' as u32).contains(&index) {
            continue;
        }
        let index = (index - b'a' as u32) as usize;
        centers[index] = [key.x, key.y];
        present[index] = true;
        min_x = min_x.min(key.x);
        max_x = max_x.max(key.x);
    }
    let scale = (max_x - min_x).max(1.0);

    let normalized_path: Vec<[f32; 2]> = path
        .iter()
        .map(|p| [p[0] / scale, p[1] / scale])
        .collect();
    let normalized_centers = centers.map(|p| [p[0] / scale, p[1] / scale]);

    let mut best_text: Option<&str> = None;
    let mut best_score = f32::INFINITY;

    for word in words() {
        let Some(base) = score_word(
            word.text,
            &normalized_path,
            &normalized_centers,
            &present,
            scale,
            &crossed,
        ) else {
            continue;
        };
        let score = base + word.prior * 0.012;
        if score < best_score {
            best_score = score;
            best_text = Some(word.text);
        }
    }

    // The user's custom vocabulary (names, products) gets a decent prior so
    // domain words win over generic English.
    for word in extra {
        let text = word.trim();
        if text.len() < 2 || text.len() > 15 || !text.chars().all(|c| c.is_ascii_lowercase()) {
            continue;
        }
        let Some(base) = score_word(
            text,
            &normalized_path,
            &normalized_centers,
            &present,
            scale,
            &crossed,
        ) else {
            continue;
        };
        let score = base + 0.05;
        if score < best_score {
            best_score = score;
            best_text = Some(text);
        }
    }

    let word = best_text?;
    if best_score > 1.6 {
        return None;
    }
    Some((word.to_string(), best_score))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn qwerty() -> Vec<Key> {
        let rows = ["qwertyuiop", "asdfghjkl", "zxcvbnm"];
        let mut keys = Vec::new();
        for (row_index, row) in rows.iter().enumerate() {
            let y = 100.0 + row_index as f32 * 100.0;
            let start_x = 60.0 + row_index as f32 * 25.0;
            for (index, c) in row.chars().enumerate() {
                keys.push(Key {
                    c,
                    x: start_x + index as f32 * 90.0,
                    y,
                });
            }
        }
        keys
    }

    fn path_for(word: &str, keys: &[Key]) -> Vec<[f32; 2]> {
        // One point per letter centre, in order. Real swipes add noise, which
        // the sequence/spatial scoring tolerates; this exercises the core.
        let mut path = Vec::new();
        for c in word.chars() {
            if let Some(key) = keys.iter().find(|k| k.c == c) {
                path.push([key.x, key.y]);
            }
        }
        path
    }

    #[test]
    fn decodes_common_words() {
        let keys = qwerty();
        for word in ["hello", "world", "the", "keyboard", "typing", "message"] {
            let path = path_for(word, &keys);
            let result = decode(&path, &keys, &[]);
            assert_eq!(
                result.as_ref().map(|(w, _)| w.as_str()),
                Some(word),
                "failed for {word}: {result:?}"
            );
        }
    }

    #[test]
    fn rejects_a_single_key() {
        let keys = qwerty();
        let path = vec![[400.0, 100.0]];
        assert!(decode(&path, &keys, &[]).is_none());
    }

    #[test]
    fn double_letters_are_tolerated() {
        let keys = qwerty();
        let path = path_for("hello", &keys);
        let crossed = crossed_letters(&path, &keys);
        assert!(crossed.starts_with(&['h']));
        assert!(crossed.ends_with(&['o']));
    }
}
