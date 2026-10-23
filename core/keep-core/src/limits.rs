const MIB: usize = 1024 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OpenLimits {
    pub max_file_size: usize,
    pub max_decompressed_size: usize,
    pub max_kdf_memory_bytes: u64,
    pub max_kdf_iterations: u64,
    pub max_kdf_parallelism: u32,
    pub max_aes_kdf_rounds: u64,
    pub max_xml_nodes: u32,
    pub max_group_depth: usize,
    pub max_groups: usize,
    pub max_entries: usize,
    pub max_field_size: usize,
    pub max_attachments: usize,
}

impl Default for OpenLimits {
    fn default() -> Self {
        Self {
            max_file_size: 256 * MIB,
            max_decompressed_size: 512 * MIB,
            max_kdf_memory_bytes: 1024 * 1024 * 1024,
            max_kdf_iterations: 10_000,
            max_kdf_parallelism: 256,
            max_aes_kdf_rounds: 1_000_000_000,
            max_xml_nodes: 20_000_000,
            max_group_depth: 128,
            max_groups: 100_000,
            max_entries: 1_000_000,
            max_field_size: 16 * MIB,
            max_attachments: 100_000,
        }
    }
}
