package com.k3s.phoneserver.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.k3s.phoneserver.R
import com.k3s.phoneserver.logging.RequestLog

class RequestLogAdapter : ListAdapter<RequestLog, RequestLogAdapter.RequestLogViewHolder>(RequestLogDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RequestLogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_request_log, parent, false)
        return RequestLogViewHolder(view)
    }

    override fun onBindViewHolder(holder: RequestLogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class RequestLogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textTimestamp: TextView = itemView.findViewById(R.id.textTimestamp)
        private val textMethod: TextView = itemView.findViewById(R.id.textMethod)
        private val textPath: TextView = itemView.findViewById(R.id.textPath)
        private val textStatusCode: TextView = itemView.findViewById(R.id.textStatusCode)
        private val textResponseTime: TextView = itemView.findViewById(R.id.textResponseTime)

        fun bind(requestLog: RequestLog) {
            textTimestamp.text = requestLog.timestamp
            textMethod.text = requestLog.method
            textPath.text = requestLog.path
            textStatusCode.text = requestLog.statusCode.toString()
            textResponseTime.text = "${requestLog.responseTime}ms"

            // Color code status codes
            val statusColor = when (requestLog.statusCode) {
                in 200..299 -> Color.parseColor("#4CAF50") // Green for success
                in 300..399 -> Color.parseColor("#FF9800") // Orange for redirect
                in 400..499 -> Color.parseColor("#F44336") // Red for client error
                in 500..599 -> Color.parseColor("#9C27B0") // Purple for server error
                else -> Color.parseColor("#757575") // Gray for unknown
            }
            textStatusCode.setTextColor(statusColor)

            // Color code methods
            val methodColor = when (requestLog.method) {
                "GET" -> Color.parseColor("#2196F3")    // Blue
                "POST" -> Color.parseColor("#4CAF50")   // Green
                "PUT" -> Color.parseColor("#FF9800")    // Orange
                "DELETE" -> Color.parseColor("#F44336") // Red
                else -> Color.parseColor("#757575")     // Gray
            }
            textMethod.setTextColor(methodColor)
        }
    }

    class RequestLogDiffCallback : DiffUtil.ItemCallback<RequestLog>() {
        override fun areItemsTheSame(oldItem: RequestLog, newItem: RequestLog): Boolean {
            return oldItem.timestamp == newItem.timestamp && oldItem.path == newItem.path
        }

        override fun areContentsTheSame(oldItem: RequestLog, newItem: RequestLog): Boolean {
            return oldItem == newItem
        }
    }
}
