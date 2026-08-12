//! KernelSU-style boot image patching for KernelPatch LKM mode.

use android_bootimg::cpio::{Cpio, CpioEntry};
use android_bootimg::parser::{BootImage, RamdiskImage};
use android_bootimg::patcher::BootImagePatchOption;
use anyhow::{Context, Result, ensure};
use clap::Args;
use std::io::Cursor;
use std::path::{Path, PathBuf};

#[derive(Args, Debug)]
pub struct PatchArgs {
    /// Source boot, init_boot, or vendor_boot image.
    #[arg(short, long)]
    pub boot: PathBuf,

    /// KernelPatch module matching the image KMI.
    #[arg(short, long)]
    pub module: PathBuf,

    /// Early init loader binary. It is installed as /init.
    #[arg(long)]
    pub loader: PathBuf,

    /// Output patched image.
    #[arg(short, long)]
    pub out: PathBuf,
}

#[derive(Args, Debug)]
pub struct RestoreArgs {
    /// Source patched boot, init_boot, or vendor_boot image.
    #[arg(short, long)]
    pub boot: PathBuf,

    /// Output restored image.
    #[arg(short, long)]
    pub out: PathBuf,
}

#[derive(Args, Debug)]
pub struct CheckArgs {
    /// Boot image to inspect.
    #[arg(short, long)]
    pub boot: PathBuf,
}

fn extract_ramdisk(ramdisk: &RamdiskImage) -> Result<(Cpio, Option<usize>)> {
    if ramdisk.is_vendor_ramdisk() {
        let (index, entry) = ramdisk
            .iter_vendor_ramdisk()
            .enumerate()
            .find(|(_, entry)| entry.get_name_raw() == b"init_boot")
            .or_else(|| {
                ramdisk
                    .iter_vendor_ramdisk()
                    .enumerate()
                    .find(|(_, entry)| entry.get_name_raw().is_empty())
            })
            .context("no suitable vendor ramdisk entry found")?;
        let mut data = Vec::new();
        entry.dump(&mut data, false)?;
        Ok((Cpio::load_from_data(&data)?, Some(index)))
    } else {
        let mut data = Vec::new();
        ramdisk.dump(&mut data, false)?;
        Ok((Cpio::load_from_data(&data)?, None))
    }
}

fn repack(
    image: &BootImage<'_>,
    cpio: &mut Cpio,
    vendor_ramdisk_index: Option<usize>,
    out: &Path,
) -> Result<()> {
    let mut ramdisk = Vec::new();
    cpio.dump(&mut ramdisk)?;

    let mut patcher = BootImagePatchOption::new(image);
    if let Some(index) = vendor_ramdisk_index {
        patcher.replace_vendor_ramdisk(index, Box::new(Cursor::new(ramdisk)), false);
    } else {
        patcher.replace_ramdisk(Box::new(Cursor::new(ramdisk)), false);
    }

    let mut output = Cursor::new(Vec::with_capacity(image.get_size()));
    patcher.patch(&mut output)?;
    std::fs::write(out, output.into_inner())
        .with_context(|| format!("write patched image {} failed", out.display()))?;
    Ok(())
}

pub fn patch(args: PatchArgs) -> Result<()> {
    ensure!(args.boot.exists(), "boot image does not exist");
    ensure!(args.module.exists(), "KernelPatch module does not exist");
    ensure!(args.loader.exists(), "LKM init loader does not exist");

    let image_data = std::fs::read(&args.boot)
        .with_context(|| format!("read boot image {} failed", args.boot.display()))?;
    let image = BootImage::parse(&image_data).context("parse boot image failed")?;
    let ramdisk = image
        .get_blocks()
        .get_ramdisk()
        .context("boot image has no ramdisk")?;
    let (mut cpio, vendor_ramdisk_index) = extract_ramdisk(ramdisk)?;

    ensure!(!cpio.is_magisk_patched(), "cannot patch a Magisk-patched image");
    if cpio.exists("init") && !cpio.exists("init.real") {
        cpio.mv("init", "init.real")?;
    }
    ensure!(cpio.exists("init.real"), "ramdisk has no original init");

    let module = std::fs::read(&args.module)
        .with_context(|| format!("read KernelPatch module {} failed", args.module.display()))?;
    let loader = std::fs::read(&args.loader)
        .with_context(|| format!("read LKM init loader {} failed", args.loader.display()))?;

    cpio.add("init", CpioEntry::regular(0o755, Box::new(loader)))?;
    cpio.add(
        "kernelpatch.ko",
        CpioEntry::regular(0o644, Box::new(module)),
    )?;
    repack(&image, &mut cpio, vendor_ramdisk_index, &args.out)?;
    println!("LKM boot image written to {}", args.out.display());
    Ok(())
}

pub fn restore(args: RestoreArgs) -> Result<()> {
    ensure!(args.boot.exists(), "boot image does not exist");

    let image_data = std::fs::read(&args.boot)
        .with_context(|| format!("read boot image {} failed", args.boot.display()))?;
    let image = BootImage::parse(&image_data).context("parse boot image failed")?;
    let ramdisk = image
        .get_blocks()
        .get_ramdisk()
        .context("boot image has no ramdisk")?;
    let (mut cpio, vendor_ramdisk_index) = extract_ramdisk(ramdisk)?;

    ensure!(cpio.exists("kernelpatch.ko"), "image is not an LKM image");
    ensure!(cpio.exists("init.real"), "image has no original init");
    cpio.rm("kernelpatch.ko", false);
    cpio.rm("init", false);
    cpio.mv("init.real", "init")?;
    repack(&image, &mut cpio, vendor_ramdisk_index, &args.out)?;
    println!("LKM boot image restored to {}", args.out.display());
    Ok(())
}

pub fn check(args: CheckArgs) -> Result<()> {
    ensure!(args.boot.exists(), "boot image does not exist");
    let image_data = std::fs::read(&args.boot)
        .with_context(|| format!("read boot image {} failed", args.boot.display()))?;
    let image = BootImage::parse(&image_data).context("parse boot image failed")?;
    let ramdisk = image
        .get_blocks()
        .get_ramdisk()
        .context("boot image has no ramdisk")?;
    let (cpio, _) = extract_ramdisk(ramdisk)?;
    ensure!(cpio.exists("init"), "ramdisk has no init");
    println!("LKM_RAMDISK=1");
    Ok(())
}
