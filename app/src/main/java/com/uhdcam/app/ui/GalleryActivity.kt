package com.uhdcam.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.uhdcam.app.R
import java.io.File

class GalleryActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView

    companion object {
        fun open(activity: Activity) {
            activity.startActivity(Intent(activity, GalleryActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        recycler = findViewById(R.id.galleryRecycler)
        emptyView = findViewById(R.id.emptyView)

        findViewById<View>(R.id.toolbar).setOnClickListener { finish() }

        recycler.layoutManager = GridLayoutManager(this, 3)
        loadImages()
    }

    private fun loadImages() {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "UHDCamera"
        )
        val images = if (dir.exists()) {
            dir.listFiles()
                ?.filter { it.extension in listOf("jpg", "jpeg", "png") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        } else emptyList()

        if (images.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            recycler.adapter = ImageAdapter(this, images) { file ->
                val uri = FileProvider.getUriForFile(
                    this, "${packageName}.fileprovider", file
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/*")
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                startActivity(intent)
            }
        }
    }

    private class ImageAdapter(
        private val context: android.content.Context,
        private val images: List<File>,
        private val onClick: (File) -> Unit
    ) : RecyclerView.Adapter<ImageAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(context)
                .inflate(R.layout.gallery_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(images[position], onClick)
        }

        override fun getItemCount() = images.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imageView: ImageView = itemView as ImageView

            fun bind(file: File, onClick: (File) -> Unit) {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 4
                }
                val bmp = BitmapFactory.decodeFile(file.absolutePath, options)
                if (bmp != null) {
                    imageView.setImageBitmap(bmp)
                    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                }
                itemView.setOnClickListener { onClick(file) }
            }
        }
    }
}
