use wekit_llama::llama::perf_core_count;

#[test]
fn clusters() {
    assert_eq!(
        perf_core_count(&[1800, 1800, 1800, 1800, 2400, 2400, 2400, 2400]),
        4
    );
    assert_eq!(perf_core_count(&[0, 0]), 1);
    assert_eq!(perf_core_count(&[]), 1);
}
