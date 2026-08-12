//! Static early-init loader for KernelSU-style LKM images.

#![no_main]

#[path = "../../apd/src/init.rs"]
mod init;
#[path = "../../apd/src/insmod.rs"]
mod insmod;

/// Keep the raw init argv/envp and never depend on Rust's std runtime entry.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn main(
    _argc: i32,
    argv: *const *const u8,
    envp: *const *const u8,
) -> i32 {
    let result = unsafe {
        init::run_raw(
            argv.cast::<*const libc::c_char>(),
            envp.cast::<*const libc::c_char>(),
        )
    };
    if let Err(error) = result {
        eprintln!("apinit fatal: {error:#}");
        unsafe {
            init::exec_fallback(
                argv.cast::<*const libc::c_char>(),
                envp.cast::<*const libc::c_char>(),
            )
        };
    }
    127
}
